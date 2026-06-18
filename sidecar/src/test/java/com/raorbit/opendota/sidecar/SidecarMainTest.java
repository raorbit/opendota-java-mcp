package com.raorbit.opendota.sidecar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link SidecarMain#resolvePort()} covering the property precedence
 * (dotted over dashed), the env fallback, and the validation of blank / non-numeric /
 * out-of-range values. Tests that rely on the default also assume the
 * {@code OPENDOTA_SIDECAR_PORT} env var is unset (it cannot be cleared in-process).
 */
class SidecarMainTest {

    private static final String DOTTED = "opendota.sidecar.port";
    private static final String DASHED = "opendota.sidecar-port";
    private static final int DEFAULT_PORT = 31337;

    @BeforeEach
    @AfterEach
    void clearProperties() {
        System.clearProperty(DOTTED);
        System.clearProperty(DASHED);
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
