package com.raorbit.opendota.http;

import com.raorbit.opendota.config.OpenDotaProperties;
import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.logging.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Wiring for the opt-in HTTP (remote-MCP) transport. Active ONLY under the {@code http} Spring
 * profile (activated by {@code spring.profiles.active=http}, which also loads
 * {@code application-http.properties}); the whole class is inert in the default stdio build.
 *
 * <p>It does two things:
 * <ul>
 *   <li>Registers {@link BearerAuthFilter} (when {@code opendota.http.auth-mode=bearer}) at
 *       highest precedence so the MCP endpoint is bearer-gated.</li>
 *   <li>Runs a <b>fail-closed startup guard</b>: if the server binds a non-loopback address
 *       without a bearer token, it logs and {@code System.exit(1)} before Tomcat accepts traffic
 *       — mirroring the sidecar's guard ({@code SidecarMain.requiresToken}).</li>
 * </ul>
 *
 * <p>The existing {@code ToolCallbackProvider} bean from {@code McpToolConfig} is reused
 * unchanged; the webmvc Streamable-HTTP transport auto-configures the MCP server over it exactly
 * as the stdio transport does. No {@code @Tool} changes are needed.
 */
@Configuration(proxyBeanMethods = false)
@Profile("http")
public class HttpMcpConfig {

    private static final Logger LOG = Logger.getLogger(HttpMcpConfig.class.getName());

    private final OpenDotaProperties properties;
    private final Environment environment;

    public HttpMcpConfig(OpenDotaProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * Fail closed BEFORE the embedded server starts accepting requests. Bean construction runs
     * during context refresh, ahead of the Tomcat WebServer start, so exiting here means the
     * port never goes live. An unresolvable bind host is treated as exposed (fail safe), exactly
     * like {@code SidecarMain.requiresToken}.
     */
    @PostConstruct
    void enforceFailClosedGuard() {
        // server.address may be unset -> Spring binds all interfaces (0.0.0.0), which is non-loopback.
        String bindHost = environment.getProperty("server.address");
        boolean exposed = isNonLoopbackBind(bindHost);
        boolean hasToken = !isBlank(properties.getHttp().getBearerToken());
        boolean bearerMode = properties.getHttp().getAuthMode() == OpenDotaProperties.Http.AuthMode.BEARER;
        // Two independent fail-closed conditions:
        //  1. Non-loopback bind without bearer-with-token -> endpoint would be exposed unauthenticated.
        //  2. Bearer mode explicitly requested but no secret set -> the filter would be a no-op
        //     (and now deny-all), so an operator asked for auth they don't actually have. Refuse
        //     regardless of bind address rather than silently running a broken auth layer.
        if (exposedWithoutAuth(exposed, bearerMode, hasToken)) {
            String shown = (bindHost == null || bindHost.isBlank()) ? "<all interfaces>" : bindHost;
            LOG.severe("refusing to start: HTTP MCP bind '" + shown + "' is not loopback but auth is "
                    + "not bearer-with-token, which would expose the /mcp endpoint unauthenticated. "
                    + "Set opendota.http.auth-mode=bearer and OPENDOTA_HTTP_BEARER_TOKEN, or bind "
                    + "server.address=127.0.0.1 behind a TLS proxy.");
            System.exit(1);
        }
        if (bearerWithoutToken(bearerMode, hasToken)) {
            LOG.severe("refusing to start: opendota.http.auth-mode=bearer was requested but no "
                    + "bearer token is configured (OPENDOTA_HTTP_BEARER_TOKEN / "
                    + "opendota.http.bearer-token is blank). The bearer filter would have no secret "
                    + "to enforce. Set the token, or set opendota.http.auth-mode=none for an "
                    + "explicitly unauthenticated loopback-only instance.");
            System.exit(1);
        }
    }

    /**
     * Register the bearer filter when {@code opendota.http.auth-mode=bearer}. Highest precedence so
     * it runs before the MCP transport handler. The bean is created ONLY in bearer mode
     * ({@link ConditionalOnProperty}); when auth-mode=none no filter bean exists at all — the
     * startup guard above has already ensured none is reachable only on a loopback bind. (A
     * {@code FilterRegistrationBean} with a null filter throws at context init, so we must skip the
     * bean entirely rather than register a disabled one.)
     */
    @Bean
    @ConditionalOnProperty(name = "opendota.http.auth-mode", havingValue = "bearer", matchIfMissing = true)
    FilterRegistrationBean<BearerAuthFilter> bearerAuthFilterRegistration() {
        FilterRegistrationBean<BearerAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new BearerAuthFilter(properties.getHttp()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static boolean isNonLoopbackBind(String bindHost) {
        if (bindHost == null || bindHost.isBlank()) {
            // Unset = bind all interfaces (0.0.0.0) = exposed.
            return true;
        }
        try {
            return !InetAddress.getByName(bindHost.trim()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            // Fail safe: an unresolvable host is treated as exposed.
            return true;
        }
    }

    /**
     * Fail-closed condition 1, factored out of the {@link System#exit} call site so it is unit-testable:
     * a non-loopback bind without bearer-with-token would expose the {@code /mcp} endpoint unauthenticated.
     */
    static boolean exposedWithoutAuth(boolean exposed, boolean bearerMode, boolean hasToken) {
        return exposed && (!bearerMode || !hasToken);
    }

    /**
     * Fail-closed condition 2, factored out of the {@link System#exit} call site so it is unit-testable:
     * bearer mode was requested but no secret is configured, so the filter would have nothing to enforce.
     */
    static boolean bearerWithoutToken(boolean bearerMode, boolean hasToken) {
        return bearerMode && !hasToken;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
