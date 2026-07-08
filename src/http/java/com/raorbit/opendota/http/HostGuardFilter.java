package com.raorbit.opendota.http;

import com.raorbit.opendota.config.OpenDotaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Anti-DNS-rebinding guard for the {@code auth-mode=none} loopback instance, mirroring the
 * sidecar's {@code hostAllowed} check. Registered only when {@code opendota.http.auth-mode=none}
 * (see {@link HttpMcpConfig}); in bearer mode the secret itself defeats a rebinding page (it
 * cannot know the bearer), so no host check is needed there.
 *
 * <p>Without this, Spring AI 1.1.8's Streamable-HTTP transport applies NO Host/Origin validation
 * ({@code ServerTransportSecurityValidator.NOOP}), so a malicious page that rebinds its own
 * hostname to {@code 127.0.0.1} could POST MCP {@code initialize} + {@code tools/call} to the
 * "loopback-only" {@code /mcp} endpoint as a same-origin request. The rebinding page still sends
 * its own hostname as {@code Host} — a forbidden header page JavaScript cannot forge — so
 * rejecting any {@code Host} that is not a loopback literal (or an explicitly allow-listed
 * proxy hostname, {@code opendota.http.allowed-hosts}) closes the hole. Values are matched as
 * literals, never DNS-resolved: resolving an attacker hostname the victim already rebound to
 * {@code 127.0.0.1} would report loopback and defeat the check.
 *
 * <p>The allow-list exists because fronting proxies/tunnels (cloudflared, Caddy) forward the
 * original public {@code Host} to the loopback app; an operator running {@code auth-mode=none}
 * behind an access-controlled tunnel sets it to that public hostname. {@code /actuator/health}
 * stays un-gated, matching {@link BearerAuthFilter}, so the proxy can probe liveness.
 */
public class HostGuardFilter extends OncePerRequestFilter {

    /**
     * Literal loopback hosts always accepted in the {@code Host} header. Any other dotted-quad in
     * {@code 127/8} is accepted via {@link #LOOPBACK_IPV4}, matching what a loopback bind accepts.
     */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    /** A literal IPv4 loopback address ({@code 127.x.y.z}) — matched textually, never resolved. */
    private static final Pattern LOOPBACK_IPV4 = Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private final Set<String> allowedHosts;

    HostGuardFilter(OpenDotaProperties.Http http) {
        Set<String> allowed = new LinkedHashSet<>();
        if (http.getAllowedHosts() != null) {
            for (String host : http.getAllowedHosts()) {
                if (host != null && !host.isBlank()) {
                    allowed.add(hostOnly(host));
                }
            }
        }
        this.allowedHosts = Set.copyOf(allowed);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Leave ONLY the actuator health endpoint un-gated, exactly like BearerAuthFilter, so a
        // fronting proxy/tunnel can probe liveness without knowing the allow-listed hostname.
        String path = request.getRequestURI();
        return "/actuator/health".equals(path) || "/actuator/health/".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!hostAllowed(request.getHeader("Host"), allowedHosts)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json; charset=utf-8");
            response.getWriter().write("{\"error\":\"forbidden\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Whether the presented {@code Host} header names this loopback instance (or an allow-listed
     * proxy hostname). Static and package-private so the decision logic is unit-testable.
     */
    static boolean hostAllowed(String hostHeader, Set<String> allowedHosts) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;   // HTTP/1.1 mandates Host; every legitimate client sends one.
        }
        String host = hostOnly(hostHeader);
        return LOOPBACK_HOSTS.contains(host)
                || LOOPBACK_IPV4.matcher(host).matches()
                || allowedHosts.contains(host);
    }

    /** The host part of a {@code Host} header, lower-cased, with any {@code :port} and IPv6 brackets removed. */
    static String hostOnly(String host) {
        String h = host.trim();
        if (h.startsWith("[")) {   // IPv6 literal, e.g. [::1]:8080
            int close = h.indexOf(']');
            return (close > 0 ? h.substring(1, close) : h).toLowerCase(Locale.ROOT);
        }
        int colon = h.indexOf(':');
        return (colon >= 0 ? h.substring(0, colon) : h).toLowerCase(Locale.ROOT);
    }
}
