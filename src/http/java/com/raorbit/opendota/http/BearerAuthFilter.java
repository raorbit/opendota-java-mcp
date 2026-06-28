package com.raorbit.opendota.http;

import com.raorbit.opendota.config.OpenDotaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces {@code Authorization: Bearer <token>} on the HTTP MCP endpoint when
 * {@code opendota.http.auth-mode=bearer}. Registered only under the {@code http} Spring profile
 * (see {@link HttpMcpConfig}), so it is wholly inert in the default stdio build.
 *
 * <p>The presented token is compared to the configured secret with a constant-time
 * {@link MessageDigest#isEqual} comparison, mirroring the sidecar's {@code authorized()} gate.
 * The actuator liveness probe ({@code /actuator/health}) is left open so a fronting
 * proxy/tunnel can check the instance is up; everything else requires the bearer.
 *
 * <p>This bearer is the app-level defense-in-depth check. Claude's in-app custom-connector OAuth
 * flow is terminated by the fronting proxy/tunnel, which injects this header; see
 * {@code docs/remote-connector.md}.
 */
public class BearerAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;

    BearerAuthFilter(OpenDotaProperties.Http http) {
        String token = http.getBearerToken();
        this.expectedToken = (token == null ? "" : token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Leave ONLY the actuator health endpoint un-gated so a proxy/tunnel can probe liveness.
        // Exact matches (not startsWith): sibling/sub paths like /actuator/health/{component} —
        // which can leak per-component status — stay behind the bearer gate.
        String path = request.getRequestURI();
        return "/actuator/health".equals(path) || "/actuator/health/".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!authorized(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json; charset=utf-8");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean authorized(HttpServletRequest request) {
        // Deny-all when no secret is configured: in bearer mode a blank token is a
        // misconfiguration, not "open access". Otherwise MessageDigest.isEqual(new byte[0],
        // new byte[0]) would authorize an empty "Authorization: Bearer " value (fail-open).
        if (expectedToken.length == 0) {
            return false;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] presented = header.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison; MessageDigest.isEqual itself does not short-circuit on length.
        return MessageDigest.isEqual(expectedToken, presented);
    }
}
