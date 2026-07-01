package com.raorbit.opendota.sidecar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link SidecarMain}'s {@code resolvePort()}, {@code resolveToken()} and
 * {@code resolveBindHost()} resolvers, covering the property precedence (dotted over dashed),
 * the env fallback, and the validation of blank / non-numeric / out-of-range values. Tests that
 * rely on a default also assume the corresponding env var is unset (it cannot be cleared in-process).
 */
class SidecarMainTest {

    private static final String DOTTED = "opendota.sidecar.port";
    private static final String DASHED = "opendota.sidecar-port";
    private static final int DEFAULT_PORT = 31337;

    private static final String TOKEN_DOTTED = "opendota.sidecar.token";
    private static final String TOKEN_DASHED = "opendota.sidecar-token";

    private static final String BIND_DOTTED = "opendota.sidecar.bind";
    private static final String BIND_DASHED = "opendota.sidecar-bind";
    private static final String DEFAULT_BIND_HOST = "127.0.0.1";

    private static final String WRITES_DOTTED = "opendota.sidecar.allow-writes";
    private static final String WRITES_DASHED = "opendota.sidecar-allow-writes";

    @BeforeEach
    @AfterEach
    void clearProperties() {
        System.clearProperty(DOTTED);
        System.clearProperty(DASHED);
        System.clearProperty(TOKEN_DOTTED);
        System.clearProperty(TOKEN_DASHED);
        System.clearProperty(BIND_DOTTED);
        System.clearProperty(BIND_DASHED);
        System.clearProperty(WRITES_DOTTED);
        System.clearProperty(WRITES_DASHED);
    }

    @Test
    void defaultsWhenNothingIsSet() {
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_PORT") == null);
        assertThat(SidecarMain.resolvePort()).isEqualTo(DEFAULT_PORT);
    }

    @Test
    void honoursTheDottedSystemProperty() {
        System.setProperty(DOTTED, "40000");
        assertThat(SidecarMain.resolvePort()).isEqualTo(40000);
    }

    @Test
    void honoursTheDashedSystemProperty() {
        // The dashed spelling matches the agent's Spring property, accepted here so the
        // two sides cannot be silently mismatched by separator.
        System.setProperty(DASHED, "40001");
        assertThat(SidecarMain.resolvePort()).isEqualTo(40001);
    }

    @Test
    void dottedWinsOverDashed() {
        System.setProperty(DOTTED, "40002");
        System.setProperty(DASHED, "40003");
        assertThat(SidecarMain.resolvePort()).isEqualTo(40002);
    }

    @Test
    void trimsSurroundingWhitespace() {
        System.setProperty(DOTTED, "  40004  ");
        assertThat(SidecarMain.resolvePort()).isEqualTo(40004);
    }

    @Test
    void blankFallsBackToDefault() {
        System.setProperty(DOTTED, "   ");
        assertThat(SidecarMain.resolvePort()).isEqualTo(DEFAULT_PORT);
    }

    @Test
    void nonNumericFallsBackToDefault() {
        System.setProperty(DOTTED, "not-a-port");
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_PORT") == null);
        assertThat(SidecarMain.resolvePort()).isEqualTo(DEFAULT_PORT);
    }

    @Test
    void resolveTokenDefaultsToNullWhenUnset() {
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_TOKEN") == null);
        assertThat(SidecarMain.resolveToken()).isNull();
    }

    @Test
    void resolveTokenReadsBothPropertySpellingsWithDottedWinning() {
        System.setProperty(TOKEN_DASHED, "dash-token");
        assertThat(SidecarMain.resolveToken()).isEqualTo("dash-token");
        System.setProperty(TOKEN_DOTTED, "dot-token");
        assertThat(SidecarMain.resolveToken()).isEqualTo("dot-token");
    }

    @Test
    void resolveBindHostDefaultsToLoopbackWhenUnset() {
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_BIND") == null);
        assertThat(SidecarMain.resolveBindHost()).isEqualTo(DEFAULT_BIND_HOST);
    }

    @Test
    void resolveBindHostReadsBothPropertySpellingsWithDottedWinning() {
        System.setProperty(BIND_DASHED, "10.0.0.5");
        assertThat(SidecarMain.resolveBindHost()).isEqualTo("10.0.0.5");
        System.setProperty(BIND_DOTTED, "0.0.0.0");
        assertThat(SidecarMain.resolveBindHost()).isEqualTo("0.0.0.0");
    }

    @Test
    void resolveBindHostTrimsAndFallsBackOnBlank() {
        System.setProperty(BIND_DOTTED, "  0.0.0.0  ");
        assertThat(SidecarMain.resolveBindHost()).isEqualTo("0.0.0.0");
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_BIND") == null);
        System.setProperty(BIND_DOTTED, "   ");
        assertThat(SidecarMain.resolveBindHost()).isEqualTo(DEFAULT_BIND_HOST);
    }

    @Test
    void requiresTokenIsFalseForLoopbackBinds() {
        assertThat(SidecarMain.requiresToken("127.0.0.1")).isFalse();
        assertThat(SidecarMain.requiresToken("::1")).isFalse();
        assertThat(SidecarMain.requiresToken("localhost")).isFalse();
    }

    @Test
    void requiresTokenIsTrueForNonLoopbackBinds() {
        assertThat(SidecarMain.requiresToken("0.0.0.0")).isTrue();
        assertThat(SidecarMain.requiresToken("10.0.0.5")).isTrue();
    }

    @Test
    void resolveAllowWritesDefaultsToTrueWhenUnset() {
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_ALLOW_WRITES") == null);
        assertThat(SidecarMain.resolveAllowWrites()).isTrue();
    }

    @Test
    void resolveAllowWritesTreatsExplicitFalseyValuesAsReadOnly() {
        for (String falsey : new String[] {"false", "FALSE", "0", "no", "off", "  false  "}) {
            System.setProperty(WRITES_DOTTED, falsey);
            assertThat(SidecarMain.resolveAllowWrites())
                    .as("'%s' should disable writes", falsey)
                    .isFalse();
        }
    }

    @Test
    void resolveAllowWritesKeepsWritesOnForTrueOrOtherValues() {
        for (String truthy : new String[] {"true", "TRUE", "yes", "1", "anything"}) {
            System.setProperty(WRITES_DOTTED, truthy);
            assertThat(SidecarMain.resolveAllowWrites())
                    .as("'%s' should keep writes on", truthy)
                    .isTrue();
        }
    }

    @Test
    void outOfRangePortsFallBackToDefault() {
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_PORT") == null);
        for (String oob : new String[] {"0", "-1", "65536", "99999"}) {
            System.setProperty(DOTTED, oob);
            assertThat(SidecarMain.resolvePort())
                    .as("out-of-range port %s must fall back to the default", oob)
                    .isEqualTo(DEFAULT_PORT);
        }
    }
}
