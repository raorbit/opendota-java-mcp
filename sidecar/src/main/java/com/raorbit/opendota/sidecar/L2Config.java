package com.raorbit.opendota.sidecar;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
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

    // --- read-connection pool ---
    private static final String READ_POOL_PROP = "opendota.sidecar.l2.readPool";
    private static final String READ_POOL_ENV = "OPENDOTA_SIDECAR_L2_READ_POOL";

    // --- patch-check cadence ---
    private static final String PATCH_INTERVAL_PROP = "opendota.sidecar.l2.patchCheckMillis";
    private static final String PATCH_INTERVAL_ENV = "OPENDOTA_SIDECAR_L2_PATCH_CHECK_MILLIS";

    // --- patch override ---
    private static final String PATCH_ID_PROP = "opendota.sidecar.patch.id";
    private static final String PATCH_ID_ENV = "OPENDOTA_SIDECAR_PATCH_ID";

    // --- watched players (a personal archive governed by its own budget; spec §6.5) ---
    private static final String WATCHED_PLAYERS_PROP = "opendota.sidecar.l2.watchedPlayers";
    private static final String WATCHED_PLAYERS_ENV = "OPENDOTA_SIDECAR_L2_WATCHED_PLAYERS";
    private static final String WATCHED_MAX_ROWS_PROP = "opendota.sidecar.l2.watchedMaxRows";
    private static final String WATCHED_MAX_ROWS_ENV = "OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS";
    private static final String WATCHED_MAX_BYTES_PROP = "opendota.sidecar.l2.watchedMaxBytes";
    private static final String WATCHED_MAX_BYTES_ENV = "OPENDOTA_SIDECAR_L2_WATCHED_MAX_BYTES";
    private static final String WATCHED_REFETCH_PROP = "opendota.sidecar.l2.watchedRefetchMillis";
    private static final String WATCHED_REFETCH_ENV = "OPENDOTA_SIDECAR_L2_WATCHED_REFETCH_MILLIS";

    /** Default cap: 50,000 rows (spec §6.4). */
    static final int DEFAULT_MAX_ROWS = 50_000;
    /** Default cap: 512 MB of total body bytes (spec §6.4). */
    static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;
    /** Default patch-check cadence: 6h — the same horizon {@code ttlFor} uses for static data (spec §5.2). */
    static final long DEFAULT_PATCH_CHECK_MILLIS = Duration.ofHours(6).toMillis();
    /**
     * Default re-check cadence for an unparsed PINNED match (spec §6.5): served from L2 for this long
     * before another upstream re-fetch is attempted to pick up the parsed body, so a match OpenDota has
     * not (yet) parsed is re-fetched at most hourly rather than on every access.
     */
    static final long DEFAULT_WATCHED_REFETCH_MILLIS = Duration.ofHours(1).toMillis();
    /** Default db filename under {@code ${user.home}/.opendota-sidecar/} (spec §6.1). */
    private static final String DEFAULT_DB_DIR = ".opendota-sidecar";
    private static final String DEFAULT_DB_FILE = "l2-cache.db";

    /**
     * The watched-player archive budget (spec §6.5): the set of watched {@code account_id}s plus the
     * archive's own row and byte caps. A match whose body mentions any of these ids is stored
     * {@link Classification#PINNED} — permanent, exempt from the main caps, and bounded only by this
     * budget. Both caps use {@code 0} to mean <em>unlimited</em> (never delete pinned rows on that
     * axis), which is the default. The id set is held as a defensive unmodifiable copy.
     */
    public record Watched(Set<Long> accountIds, long maxRows, long maxBytes) {
        /** No watched players, so no PINNED rows are ever written (the feature is inert). */
        public static final Watched NONE = new Watched(Set.of(), 0, 0);

        public Watched {
            // Defensive unmodifiable copy so a caller can't mutate the set after construction.
            accountIds = accountIds == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(accountIds));
        }
    }

    private final Path dbPath;
    private final int maxRows;
    private final long maxBytes;
    private final long patchCheckMillis;
    private final String patchIdOverride;
    private final int readPoolSize;
    private final Watched watched;
    private final long watchedRefetchMillis;

    public L2Config(Path dbPath, int maxRows, long maxBytes, long patchCheckMillis, String patchIdOverride) {
        this(dbPath, maxRows, maxBytes, patchCheckMillis, patchIdOverride, L2Store.DEFAULT_READ_POOL, Watched.NONE);
    }

    public L2Config(Path dbPath, int maxRows, long maxBytes, long patchCheckMillis, String patchIdOverride,
                    int readPoolSize) {
        this(dbPath, maxRows, maxBytes, patchCheckMillis, patchIdOverride, readPoolSize, Watched.NONE);
    }

    public L2Config(Path dbPath, int maxRows, long maxBytes, long patchCheckMillis, String patchIdOverride,
                    int readPoolSize, Watched watched) {
        this(dbPath, maxRows, maxBytes, patchCheckMillis, patchIdOverride, readPoolSize, watched,
                DEFAULT_WATCHED_REFETCH_MILLIS);
    }

    public L2Config(Path dbPath, int maxRows, long maxBytes, long patchCheckMillis, String patchIdOverride,
                    int readPoolSize, Watched watched, long watchedRefetchMillis) {
        this.dbPath = dbPath;
        this.maxRows = maxRows;
        this.maxBytes = maxBytes;
        this.patchCheckMillis = patchCheckMillis;
        this.patchIdOverride = patchIdOverride;
        this.readPoolSize = readPoolSize;
        this.watched = watched == null ? Watched.NONE : watched;
        // 0 is allowed (and used in tests) to mean "re-fetch on every access" — the pre-retry-after
        // behaviour; negative is coerced to 0.
        this.watchedRefetchMillis = Math.max(0L, watchedRefetchMillis);
    }

    /** Whether the L2 tier is enabled via {@code OPENDOTA_SIDECAR_L2=true} / {@code -Dopendota.sidecar.l2=true}. */
    public static boolean isEnabled() {
        return isTruthy(trimToNull(resolve(FLAG_PROP, FLAG_ENV, null)));
    }

    /**
     * Accept the common truthy/falsy spellings (so {@code 1}/{@code yes}/{@code on} enable L2, not just
     * the exact {@code true} that {@link Boolean#parseBoolean} requires); warn and default to off on an
     * unrecognized non-blank value rather than silently disabling.
     */
    private static boolean isTruthy(String raw) {
        if (raw == null) {
            return false;
        }
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "true": case "1": case "yes": case "on":
                return true;
            case "false": case "0": case "no": case "off":
                return false;
            default:
                LOG.warning(() -> "L2 config " + FLAG_PROP + "='" + raw + "' is not a recognized boolean; L2 disabled");
                return false;
        }
    }

    /** Build a config from system properties / environment with the spec's defaults. */
    public static L2Config fromEnvironment() {
        Watched watched = new Watched(
                parseWatchedPlayers(resolve(WATCHED_PLAYERS_PROP, WATCHED_PLAYERS_ENV, null)),
                resolveCap(WATCHED_MAX_ROWS_PROP, WATCHED_MAX_ROWS_ENV),
                resolveCap(WATCHED_MAX_BYTES_PROP, WATCHED_MAX_BYTES_ENV));
        return new L2Config(
                resolveDbPath(),
                resolveInt(MAX_ROWS_PROP, MAX_ROWS_ENV, DEFAULT_MAX_ROWS),
                resolveLong(MAX_BYTES_PROP, MAX_BYTES_ENV, DEFAULT_MAX_BYTES),
                resolveLong(PATCH_INTERVAL_PROP, PATCH_INTERVAL_ENV, DEFAULT_PATCH_CHECK_MILLIS),
                trimToNull(resolve(PATCH_ID_PROP, PATCH_ID_ENV, null)),
                resolveInt(READ_POOL_PROP, READ_POOL_ENV, L2Store.DEFAULT_READ_POOL),
                watched,
                resolveRefetch(WATCHED_REFETCH_PROP, WATCHED_REFETCH_ENV, DEFAULT_WATCHED_REFETCH_MILLIS));
    }

    /**
     * Parse a comma-separated list of watched Steam32 {@code account_id}s into a deduped, insertion-
     * ordered, unmodifiable set. Blank entries are skipped; a non-numeric or non-positive entry logs a
     * warning and is skipped (the valid ids are kept). Non-positive ids are rejected because a Steam32
     * {@code account_id} is always positive and {@code 0} is OpenDota's anonymized-player sentinel —
     * watching {@code 0} would pin essentially every match with an anonymized player. {@code null}/blank
     * input yields an empty unmodifiable set.
     */
    public static Set<Long> parseWatchedPlayers(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : trimmed.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(t);
                if (id <= 0) {
                    LOG.warning(() -> "L2 config " + WATCHED_PLAYERS_PROP + " entry '" + t
                            + "' is not a positive Steam32 account_id (0 is the anonymized-player "
                            + "sentinel); skipping it");
                    continue;
                }
                ids.add(id);
            } catch (NumberFormatException e) {
                LOG.warning(() -> "L2 config " + WATCHED_PLAYERS_PROP + " entry '" + t
                        + "' is not a numeric account_id; skipping it");
            }
        }
        return Collections.unmodifiableSet(ids);
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

    /** Number of read-only connections in the L2 read pool (spec §7.1); {@link L2Store} clamps the value. */
    public int readPoolSize() {
        return readPoolSize;
    }

    /** The watched-player archive budget (ids + the archive's own row/byte caps); spec §6.5. */
    public Watched watched() {
        return watched;
    }

    /** The watched {@code account_id}s; empty when no archive is configured. */
    public Set<Long> watchedAccountIds() {
        return watched.accountIds();
    }

    /** Watched-archive row cap; {@code 0} = unlimited (never delete pinned rows for row count). */
    public long watchedMaxRows() {
        return watched.maxRows();
    }

    /** Watched-archive byte cap; {@code 0} = unlimited (never delete pinned rows for byte total). */
    public long watchedMaxBytes() {
        return watched.maxBytes();
    }

    /**
     * How long (ms) an unparsed PINNED match is served from L2 before another upstream re-fetch is
     * attempted to upgrade it to the parsed body. {@code 0} = re-fetch on every access.
     */
    public long watchedRefetchMillis() {
        return watchedRefetchMillis;
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

    /**
     * Resolve a "cap" knob where {@code 0} expresses <em>unlimited</em> (never delete) — unlike
     * {@link #resolveLong}, which rejects {@code <= 0}. Unset/blank → {@code 0}; the explicit keywords
     * {@code unlimited}/{@code none}/{@code never} (case-insensitive) → {@code 0}; a valid {@code >= 0}
     * number → use it; a negative or non-numeric value logs a warning and falls back to {@code 0}.
     * So "never delete" is expressible by leaving the knob blank or naming it explicitly, and any
     * positive value is a hard limit on the watched archive.
     */
    static long resolveCap(String prop, String env) {
        String raw = trimToNull(resolve(prop, env, null));
        if (raw == null) {
            return 0;
        }
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "unlimited": case "none": case "never":
                return 0;
            default:
                // fall through to numeric parse
        }
        try {
            long v = Long.parseLong(raw);
            if (v < 0) {
                LOG.warning(() -> "L2 config " + prop + "='" + raw + "' must be >= 0; treating as unlimited (0)");
                return 0;
            }
            return v;
        } catch (NumberFormatException e) {
            LOG.warning(() -> "L2 config " + prop + "='" + raw + "' is not a number; treating as unlimited (0)");
            return 0;
        }
    }

    /**
     * Resolve the watched re-fetch knob, where {@code 0} is the documented "re-fetch on every access"
     * value and so must be accepted (unlike {@link #resolveLong}, which rejects {@code <= 0} and would
     * coerce the {@code 0} back to the 1h default). Unlike {@link #resolveCap}, the unset default is the
     * 1h {@code fallback}, not {@code 0}: an operator who never sets the knob gets hourly re-checks, while
     * an explicit {@code 0} opts into the old re-fetch-every-access behaviour. Unset/blank → {@code fallback};
     * {@code 0} or a positive number → that value; a negative or non-numeric value warns and falls back.
     */
    static long resolveRefetch(String prop, String env, long fallback) {
        String raw = trimToNull(resolve(prop, env, null));
        if (raw == null) {
            return fallback;
        }
        try {
            long v = Long.parseLong(raw);
            if (v < 0) {
                LOG.warning(() -> "L2 config " + prop + "='" + raw + "' must be >= 0; using default " + fallback);
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
