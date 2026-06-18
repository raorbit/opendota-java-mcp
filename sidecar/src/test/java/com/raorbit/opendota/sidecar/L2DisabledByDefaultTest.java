package com.raorbit.opendota.sidecar;

import com.raorbit.opendota.client.OpenDotaClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Gate 12 (spec §10): with the feature flag off (the default), the durable tier stays inert — no
 * gateway is built, so no SQLite file is opened and the sidecar behaves exactly as the bare-client
 * version. A regression guard so the deferred tier cannot silently activate.
 */
class L2DisabledByDefaultTest {

    private static final String FLAG_PROP = "opendota.sidecar.l2";
    private static final String DB_PROP = "opendota.sidecar.l2.db";

    @BeforeEach
    @AfterEach
    void clearProperties() {
        System.clearProperty(FLAG_PROP);
        System.clearProperty(DB_PROP);
    }

    @Test
    void flagOffMeansNoGatewayAndNoSqliteFile(@TempDir Path tmp) throws Exception {
        // The env var cannot be cleared in-process; this test only makes sense when it is unset.
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_L2") == null);
        Path db = tmp.resolve("should-not-exist.db");
        System.setProperty(DB_PROP, db.toString());   // would be used if (wrongly) enabled

        assertThat(L2Config.isEnabled()).isFalse();

        // The public constructor targets the real API base, but this test never makes a call —
        // it only checks gateway construction and the on-disk db file.
        try (OpenDotaClient client = new OpenDotaClient(null)) {
            L2CachingGateway gw = SidecarMain.maybeBuildGateway(client);
            assertThat(gw).as("no gateway is built when the flag is off").isNull();
        }
        // No SQLite file (or its -wal/-shm siblings) was created.
        assertThat(Files.exists(db)).isFalse();
        assertThat(Files.exists(Path.of(db + "-wal"))).isFalse();
    }

    @Test
    void flagAcceptsCommonTruthyAndFalsySpellings() {
        // A system property overrides the env var in resolve(), so these are authoritative.
        for (String yes : new String[] {"true", "TRUE", "1", "yes", "on", " true "}) {
            System.setProperty(FLAG_PROP, yes);
            assertThat(L2Config.isEnabled()).as("'%s' enables L2", yes).isTrue();
        }
        for (String no : new String[] {"false", "0", "no", "off", "maybe", ""}) {
            System.setProperty(FLAG_PROP, no);
            assertThat(L2Config.isEnabled()).as("'%s' does not enable L2", no).isFalse();
        }
    }

    @Test
    void flagOnBuildsGatewayAndOpensTheConfiguredDb(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        System.setProperty(FLAG_PROP, "true");
        System.setProperty(DB_PROP, db.toString());

        assertThat(L2Config.isEnabled()).isTrue();

        // The public constructor targets the real API base, but this test never makes a call —
        // it only checks gateway construction and the on-disk db file.
        try (OpenDotaClient client = new OpenDotaClient(null)) {
            L2CachingGateway gw = SidecarMain.maybeBuildGateway(client);
            assertThat(gw).as("a gateway is built when the flag is on").isNotNull();
            gw.close();
        }
        // The configured db file was created (proving the flag routed through L2Store).
        assertThat(Files.exists(db)).isTrue();
    }
}
