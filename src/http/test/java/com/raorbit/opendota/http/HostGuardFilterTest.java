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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code auth-mode=none} anti-DNS-rebinding Host guard. Compile and run only
 * under {@code -Phttp} (servlet API). They pin the literal-loopback acceptance (including the
 * bracketed IPv6 and non-{@code .1} loopback forms), the rebinding rejection, and the proxied
 * public-hostname allow-list.
 */
class HostGuardFilterTest {

    private static HostGuardFilter filterAllowing(String... hosts) {
        OpenDotaProperties.Http http = new OpenDotaProperties.Http();
        http.setAllowedHosts(List.of(hosts));
        return new HostGuardFilter(http);
    }

    private static HttpServletResponse mockResponse() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }

    // --- doFilterInternal: the accept/reject decision ---

    @Test
    void loopbackHostsPassThrough() throws Exception {
        // Every literal loopback spelling a local client can present, with and without a port.
        // (IPv6 must be bracketed per RFC 7230 — a bare "::1" Host is malformed and stays rejected.)
        for (String host : new String[] {"127.0.0.1", "127.0.0.1:8080", "localhost", "localhost:8080",
                "[::1]:8080", "[::1]", "127.0.0.2:8080"}) {
            HostGuardFilter filter = filterAllowing();
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("Host")).thenReturn(host);
            HttpServletResponse response = mockResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    @Test
    void rebindingHostIsRejected() throws Exception {
        // A DNS-rebinding page sends its OWN hostname as Host — refuse it before the MCP transport.
        assertRejected("evil.example.com:8080");
        assertRejected("evil.example.com");
        // A hostname is never resolved: even one that WOULD resolve to loopback stays rejected.
        assertRejected("localtest.me:8080");
    }

    @Test
    void missingHostIsRejected() throws Exception {
        assertRejected(null);
        assertRejected("  ");
    }

    @Test
    void allowListedProxyHostnamePasses() throws Exception {
        // The documented tunnel setup forwards the public Host; the operator allow-lists it.
        HostGuardFilter filter = filterAllowing("Opendota.Example.com");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Host")).thenReturn("opendota.example.com:443");
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private static void assertRejected(String hostHeader) throws Exception {
        HostGuardFilter filter = filterAllowing();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Host")).thenReturn(hostHeader);
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(request, response);
    }

    // --- the browser cross-origin (CSRF) guard, mirroring the sidecar's policy ---

    @Test
    void crossOriginBrowserRequestsAreRejectedDespiteALoopbackHost() throws Exception {
        // A direct fetch(..., {mode:'no-cors'}) from any open page carries a legitimate loopback Host,
        // so the rebinding check alone passes it — the forbidden fetch-metadata/Origin headers are the
        // only signal, and they must be refused. Sec-Fetch-Site covers modern browsers; a bare Origin
        // covers older ones on cross-origin POSTs.
        assertRejectedWithBrowserHeaders("cross-site", null);
        assertRejectedWithBrowserHeaders("same-site", null);           // another localhost port
        assertRejectedWithBrowserHeaders("cross-site", "https://evil.example");
        assertRejectedWithBrowserHeaders(null, "https://evil.example");   // Origin-only (older browser)
        assertRejectedWithBrowserHeaders(null, "null");                   // sandboxed/opaque origin
    }

    @Test
    void nonBrowserAndUserNavigationRequestsStillPass() throws Exception {
        // A local MCP client sends neither header; a user-typed navigation sends Sec-Fetch-Site: none.
        assertPassesWithBrowserHeaders(null, null);
        assertPassesWithBrowserHeaders("none", null);
        assertPassesWithBrowserHeaders("same-origin", null);
    }

    @Test
    void browserCrossOriginBlockedDecisionTable() {
        assertThat(HostGuardFilter.browserCrossOriginBlocked(null, null)).isFalse();
        assertThat(HostGuardFilter.browserCrossOriginBlocked("none", null)).isFalse();
        assertThat(HostGuardFilter.browserCrossOriginBlocked("same-origin", null)).isFalse();
        assertThat(HostGuardFilter.browserCrossOriginBlocked(" Same-Origin ", null)).isFalse();
        assertThat(HostGuardFilter.browserCrossOriginBlocked("cross-site", null)).isTrue();
        assertThat(HostGuardFilter.browserCrossOriginBlocked("same-site", null)).isTrue();
        // Any Origin at all is refused: this server serves no pages, so no same-origin browser
        // request legitimately exists — exactly the sidecar's rule.
        assertThat(HostGuardFilter.browserCrossOriginBlocked(null, "http://localhost:3000")).isTrue();
        assertThat(HostGuardFilter.browserCrossOriginBlocked("none", "null")).isTrue();
    }

    private static void assertRejectedWithBrowserHeaders(String secFetchSite, String origin) throws Exception {
        HostGuardFilter filter = filterAllowing();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Host")).thenReturn("127.0.0.1:8080");   // legitimate loopback Host
        when(request.getHeader("Sec-Fetch-Site")).thenReturn(secFetchSite);
        when(request.getHeader("Origin")).thenReturn(origin);
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(request, response);
    }

    private static void assertPassesWithBrowserHeaders(String secFetchSite, String origin) throws Exception {
        HostGuardFilter filter = filterAllowing();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Host")).thenReturn("127.0.0.1:8080");
        when(request.getHeader("Sec-Fetch-Site")).thenReturn(secFetchSite);
        when(request.getHeader("Origin")).thenReturn(origin);
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    // --- shouldNotFilter: only the exact health endpoint is left un-gated ---

    @Test
    void onlyExactActuatorHealthIsUngated() {
        HostGuardFilter filter = filterAllowing();
        assertThat(shouldNotFilter(filter, "/actuator/health")).isTrue();
        assertThat(shouldNotFilter(filter, "/actuator/health/")).isTrue();
        assertThat(shouldNotFilter(filter, "/actuator/health/db")).isFalse();
        assertThat(shouldNotFilter(filter, "/mcp")).isFalse();
    }

    private static boolean shouldNotFilter(HostGuardFilter filter, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return filter.shouldNotFilter(request);
    }
}
