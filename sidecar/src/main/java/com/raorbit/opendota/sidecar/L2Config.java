package com.raorbit.opendota.sidecar;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Configuration for the durable L2 SQLite cache tier (see {@code docs/l2-cache-design.md} §6).
 *
 * <p>All knobs are sourced, in this order, from a JVM system property, then an environment
 * variable, then a built-in default. Invalid numeric values log a warning and fall back to the
 * default rather than crashing the sidecar — L2 is a best-effort accelerator, never a dependency.
 *
 * <p>The feature flag itself ({@code OPENDOTA_SIDECAR_L2} / {@code -Dopendota.sidecar.l2}) is read
 * by {@link #isEnabled()} and decided in {@code SidecarMain}; when off, no SQLite file is opened.
 */
public final class L2Config {

    private static final Logger LOG = Logger.getLogger(L2Config.class.getName());

    // --- feature flag ---
    private static final String FLAG_PROP = "opendota.sidecar.l2";
    private static final String FLAG_ENV = "OPENDOTA_SIDECAR_L2";

    // --- db path ---
    private static final String DB_PROP = "opendota.sidecar.l2.db";
    private static final String DB_ENV = "OPENDOTA_SIDECAR_L2_DB";

    // --- caps ---
    private static final String MAX_ROWS_PROP = "opendota.sidecar.l2.maxRows";
    private static final String MAX_ROWS_ENV = "OPENDOTA_SIDECAR_L2_MAX_ROWS";
    private static final String MAX_BYTES_PROP = "opendota.sidecar.l2.maxBytes";
    private static final String MAX_BYTES_ENV = "OPENDOTA_SIDECAR_L2_MAX_BYTES";

    // --- patch-check cadence ---
    private static final String PATCH_INTERVAL_PROP = "opendota.sidecar.l2.patchCheckMillis";
    private static final String PATCH_INTERVAL_ENV = "OPENDOTA_SIDECAR_L2_PATCH_CHECK_MILLIS";

    // --- patch override ---
    private static final String PATCH_ID_PROP = "opendota.sidecar.patch.id";
    private static final String PATCH_ID_ENV = "OPENDOTA_SIDECAR_PATCH_ID";

    /** Default cap: 50,000 rows (spec §6.4). */
    static final int DEFAULT_MAX_ROWS = 50_000;
    /** Default cap: 512 MB of total body bytes (spec §6.4). */
    static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;
    /** Default patch-check cadence: 6h — the same horizon {@code ttlFor} uses for static data (spec §5.2). */
    static final long DEFAULT_PATCH_CHECK_MILLIS = Duration.ofHours(6).toMillis();
    /** Default db filename under {@code ${user.home}/.opendota-sidecar/} (spec §6.1). */
    private static final String DEFAULT_DB_DIR = ".opendota-sidecar";
    private static final String DEFAULT_DB_FILE = "l2-cache.db";

    private final Path dbPath;
    private final int maxRows;
    private final long maxBytes;
    private final long patchCheckMillis;
    private final String patchIdOverride;

    public L2Config(Path dbPath, int maxRows, long maxBytes, long patchCheckMillis, String patchIdOverride) {
        this.dbPath = dbPath;
        this.maxRows = maxRows;
        this.maxBytes = maxBytes;
        this.patchCheckMillis = patchCheckMillis;
        this.patchIdOverride = patchIdOverride;
    }

    /** Whether the L2 tier is enabled via {@code OPENDOTA_SIDECAR_L2=true} / {@code -Dopendota.sidecar.l2=true}. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(resolve(FLAG_PROP, FLAG_ENV, "false"));
    }

    /** Build a config from system properties / environment with the spec's defaults. */
    public static L2Config fromEnvironment() {
        return new L2Config(
                resolveDbPath(),
                resolveInt(MAX_ROWS_PROP, MAX_ROWS_ENV, DEFAULT_MAX_ROWS),
                resolveLong(MAX_BYTES_PROP, MAX_BYTES_ENV, DEFAULT_MAX_BYTES),
                resolveLong(PATCH_INTERVAL_PROP, PATCH_INTERVAL_ENV, DEFAULT_PATCH_CHECK_MILLIS),
                trimToNull(resolve(PATCH_ID_PROP, PATCH_ID_ENV, null)));
    }

    public Path dbPath() {
        return dbPath;
    }

    public int maxRows() {
        return maxRows;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public long patchCheckMillis() {
        return patchCheckMillis;
    }

    /** The operator-supplied current patch id, or {@code null} to discover it from {@code /constants/patch}. */
    public String patchIdOverride() {
        return patchIdOverride;
    }

    private static Path resolveDbPath() {
        String configured = trimToNull(resolve(DB_PROP, DB_ENV, null));
        if (configured != null) {
            return Paths.get(configured);
        }
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, DEFAULT_DB_DIR, DEFAULT_DB_FILE);
    }

    private static String resolve(String prop, String env, String fallback) {
        String v = System.getProperty(prop);
        if (v == null && env != null) {
            v = System.getenv(env);
        }
        return v != null ? v : fallback;
    }

    private static int resolveInt(String prop, String env, int fallback) {
        String raw = trimToNull(resolve(prop, env, null));
        if (raw == null) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(raw);
            if (v <= 0) {
                LOG.warning(() -> "L2 config " + prop + "='" + raw + "' must be positive; using default " + fallback);
                return fallback;
            }
            return v;
        } catch (NumberFormatException e) {
            LOG.warning(() -> "L2 config " + prop + "='" + raw + "' is not a number; using default " + fallback);
            return fallback;
        }
    }

    private static long resolveLong(String prop, String env, long fallback) {
        String raw = trimToNull(resolve(prop, env, null));
        if (raw == null) {
            return fallback;
        }
        try {
            long v = Long.parseLong(raw);
            if (v <= 0) {
                LOG.warning(() -> "L2 config " + prop + "='" + raw + "' must be positive; using default " + fallback);
                return fallback;
            }
            return v;
        } catch (NumberFormatException e) {
            LOG.warning(() -> "L2 config " + prop + "='" + raw + "' is not a number; using default " + fallback);
            return fallback;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
