package com.raorbit.opendota.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the fail-closed startup guard's pure decision logic. Compiled and run only under
 * {@code -Phttp}. The {@link System#exit} call site itself is not exercised; the decision was factored
 * into pure predicates precisely so the (exposed / bearer / token) truth table can be asserted directly.
 */
class HttpMcpConfigTest {

    @Test
    void loopbackBindsAreNotExposed() {
        assertThat(HttpMcpConfig.isNonLoopbackBind("127.0.0.1")).isFalse();
        assertThat(HttpMcpConfig.isNonLoopbackBind("::1")).isFalse();
        assertThat(HttpMcpConfig.isNonLoopbackBind("localhost")).isFalse();
        assertThat(HttpMcpConfig.isNonLoopbackBind(" 127.0.0.1 ")).isFalse();   // trimmed before resolving
    }

    @Test
    void unsetOrNonLoopbackBindsAreExposed() {
        assertThat(HttpMcpConfig.isNonLoopbackBind(null)).isTrue();        // unset -> binds all interfaces
        assertThat(HttpMcpConfig.isNonLoopbackBind("")).isTrue();
        assertThat(HttpMcpConfig.isNonLoopbackBind("0.0.0.0")).isTrue();   // wildcard, not loopback
        assertThat(HttpMcpConfig.isNonLoopbackBind("203.0.113.7")).isTrue();   // TEST-NET routable literal
    }

    @Test
    void condition1RefusesAnExposedBindWithoutBearerAndToken() {
        assertThat(HttpMcpConfig.exposedWithoutAuth(true, true, true)).isFalse();    // exposed but properly authed
        assertThat(HttpMcpConfig.exposedWithoutAuth(true, true, false)).isTrue();    // exposed, bearer, no secret
        assertThat(HttpMcpConfig.exposedWithoutAuth(true, false, true)).isTrue();    // exposed, auth=none
        assertThat(HttpMcpConfig.exposedWithoutAuth(false, false, false)).isFalse(); // loopback -> never condition 1
    }

    @Test
    void condition2RefusesBearerModeWithoutToken() {
        assertThat(HttpMcpConfig.bearerWithoutToken(true, false)).isTrue();    // bearer requested, no secret
        assertThat(HttpMcpConfig.bearerWithoutToken(true, true)).isFalse();
        assertThat(HttpMcpConfig.bearerWithoutToken(false, false)).isFalse();  // auth=none, token irrelevant
    }
}
