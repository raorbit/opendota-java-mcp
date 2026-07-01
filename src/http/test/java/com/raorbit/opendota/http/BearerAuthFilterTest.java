package com.raorbit.opendota.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.raorbit.opendota.config.OpenDotaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the http-only bearer auth filter. These compile and run only under {@code -Phttp}
 * (the class depends on the servlet API the webmvc starter brings). They pin the two branches most
 * prone to silently regressing into a fail-open: the deny-all-on-blank-token rule and the
 * exact-vs-prefix {@code /actuator/health} exemption.
 */
class BearerAuthFilterTest {

    private static BearerAuthFilter filterWithToken(String configured) {
        OpenDotaProperties.Http http = new OpenDotaProperties.Http();
        http.setBearerToken(configured);
        return new BearerAuthFilter(http);
    }

    private static HttpServletResponse mockResponse() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }

    // --- doFilterInternal: the auth decision ---

    @Test
    void correctBearerTokenPassesThrough() throws Exception {
        BearerAuthFilter filter = filterWithToken("s3cret");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer s3cret");
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        assertRejected("Bearer wrong", "s3cret");
    }

    @Test
    void missingHeaderIsRejected() throws Exception {
        assertRejected(null, "s3cret");
    }

    @Test
    void nonBearerSchemeIsRejected() throws Exception {
        assertRejected("Basic s3cret", "s3cret");
    }

    @Test
    void emptyPresentedTokenIsRejected() throws Exception {
        assertRejected("Bearer ", "s3cret");
    }

    @Test
    void blankConfiguredTokenDeniesEverything() throws Exception {
        // Deny-all when no secret is configured: a bare "Bearer " must NOT authorize (otherwise
        // MessageDigest.isEqual(new byte[0], new byte[0]) would fail open), and neither may any token.
        assertRejected("Bearer ", "");
        assertRejected("Bearer anything", null);
    }

    private static void assertRejected(String authHeader, String configuredToken) throws Exception {
        BearerAuthFilter filter = filterWithToken(configuredToken);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(authHeader);
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    // --- shouldNotFilter: only the exact health endpoint is left un-gated ---

    @Test
    void onlyExactActuatorHealthIsUngated() {
        BearerAuthFilter filter = filterWithToken("s3cret");
        assertThat(shouldNotFilter(filter, "/actuator/health")).isTrue();
        assertThat(shouldNotFilter(filter, "/actuator/health/")).isTrue();
        // Sub-paths (per-component health) and look-alikes stay behind the bearer gate.
        assertThat(shouldNotFilter(filter, "/actuator/health/db")).isFalse();
        assertThat(shouldNotFilter(filter, "/actuator/healthz")).isFalse();
        assertThat(shouldNotFilter(filter, "/mcp")).isFalse();
    }

    private static boolean shouldNotFilter(BearerAuthFilter filter, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return filter.shouldNotFilter(request);
    }
}
