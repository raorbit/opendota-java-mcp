package com.raorbit.opendota.sidecar;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The durable L2 cache decorator (see {@code docs/l2-cache-design.md} §2, §3).
 *
 * <p>Wraps a single {@link OpenDotaClient}: on each {@link #get} it classifies the path, consults
 * the SQLite {@link L2Store} for PERMANENT and (non-expired) TTL hits, delegates misses to
 * {@code client.getJson} (where L1 + single-flight + the rate limiter still run, unchanged),
 * parse-gates {@code /matches/{id}} bodies, and conditionally stores the result — PERMANENT with no
 * expiry, TTL with {@code expires_at = now + ttlFor(path)} for cross-restart warmth within the TTL
 * window. L2 is a best-effort accelerator: any {@link SQLException} is counted, logged at warning,
 * and never fails the request.
 *
 * <p>The four copied client classes are untouched; all L2 logic lives here, around the client.
 */
public final class L2CachingGateway implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(L2CachingGateway.class.getName());

    /** A finished, parsed match never changes, so a PERMANENT match row never expires by time. */
    private static final long NO_EXPIRY = -1L;

    // --- classify() prefix matching ---
    /** {@code /matches/} + a non-empty run of digits and nothing further (spec §4). */
    private static final Pattern MATCH_ID = Pattern.compile("/matches/\\d+");
    /**
     * Parse-gate primary signal (spec §5.1): a top-level {@code "version"} key whose value is a
     * (non-null) number. {@code null} or absent ⇒ unparsed.
     */
    private static final Pattern PARSED_VERSION = Pattern.compile("\"version\"\\s*:\\s*\\d");
    /** Latest-patch probe for {@code /constants/patch}: numeric {@code "id"} values (dep-free, not a parse). */
    private static final Pattern PATCH_ID_NUM = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    /**
     * Precompiled corroborating parse-field probes — each is genuinely present ONLY on a parsed match
     * (verified against a live unparsed body). {@code od_data} is deliberately NOT a corroborator: it
     * is present (as {@code {"has_parsed":false,...}}) on unparsed matches, so its mere presence carries
     * no signal; we read {@code has_parsed:true} instead, the field OpenDota actually flips on parse.
     */
    private static final Pattern HAS_PARSED_TRUE = Pattern.compile("\"has_parsed\"\\s*:\\s*true");
    private static final Pattern OBJECTIVES_KEY = Pattern.compile("\"objectives\"\\s*:\\s*");
    private static final Pattern PURCHASE_LOG_KEY = Pattern.compile("\"purchase_log\"\\s*:\\s*");

    private final OpenDotaClient client;
    private final L2Store store;
    private final L2Config config;

    // --- counters (spec §8) ---
    private final AtomicLong l2Hit = new AtomicLong();
    private final AtomicLong l2Miss = new AtomicLong();
    private final AtomicLong l2Store = new AtomicLong();
    private final AtomicLong l2StoreSkippedUnparsed = new AtomicLong();
    private final AtomicLong l2PatchBust = new AtomicLong();
    private final AtomicLong l2Error = new AtomicLong();
    private final AtomicLong noStore = new AtomicLong();

    // --- patch-check bookkeeping (spec §5.2) ---
    /** Last time (epoch millis) a patch check ran; {@code 0} means never. */
    private volatile long lastPatchCheckMillis;
    /** Guards a single in-flight patch check so concurrent PERMANENT requests don't all check at once. */
    private final AtomicBoolean patchCheckInProgress = new AtomicBoolean(false);
    /** Paths with a store in flight, so concurrent single-flight misses collapse to one write. */
    private final Set<String> storing = ConcurrentHashMap.newKeySet();

    public L2CachingGateway(OpenDotaClient client, L2Store store, L2Config config) {
        this.client = client;
        this.store = store;
        this.config = config;
    }

    /**
     * Return the raw JSON body, serving from L2 when possible, else delegating to the client.
     *
     * @param path the OpenDota path (incl. query) the sidecar forwards, e.g. {@code /matches/123}
     */
    public String get(String path) throws OpenDotaException {
        Classification c = classify(path);
        if (c == Classification.NO_STORE) {
            // Pure passthrough to the client: L1 + single-flight + the rate limiter still apply.
            noStore.incrementAndGet();
            return client.getJson(path);
        }

        // PERMANENT or TTL. Patch-scoping applies ONLY to PERMANENT static data; TTL paths are
        // never patch-scoped (isPatchScoped is false for every TTL path), so no patch check runs.
        // For patch-scoped static data, run the lazy patch check first so a stale generation is
        // busted before we read it (and the per-row guard catches any survivor).
        boolean patchScoped = isPatchScoped(path);
        String currentPatchId = null;
        if (patchScoped) {
            currentPatchId = maybeCheckPatch();
        }

        // L2 read first (lookup already applies the expires_at predicate for TTL rows).
        L2Store.Entry hit = lookup(path, patchScoped, currentPatchId);
        if (hit != null) {
            l2Hit.incrementAndGet();
            return hit.body();
        }

        // Miss: delegate to the client (L1 / single-flight / rate limiting happen here).
        l2Miss.incrementAndGet();
        String body = client.getJson(path);

        // Conditional store: PERMANENT parse-gates matches; TTL stamps expires_at.
        maybeStore(path, body, c, patchScoped, currentPatchId);
        return body;
    }

    /** L2 lookup applying the read-time validity predicates (TTL, stale schema, stale patch_id). */
    private L2Store.Entry lookup(String path, boolean patchScoped, String currentPatchId) {
        try {
            L2Store.Entry e = store.get(path);
            if (e == null) {
                return null;
            }
            // Stale schema stamp (belt-and-braces; survives a partial rebuild) ⇒ treat as miss.
            if (e.schemaVersion() != L2Store.SCHEMA_VERSION) {
                return null;
            }
            // TTL expiry: PERMANENT rows have expires_at NULL and never expire by time.
            if (e.expiresAt() != null && e.expiresAt() <= System.currentTimeMillis()) {
                return null;
            }
            // Per-row stale patch_id guard (spec §5.2 defence-in-depth): a static row whose stored
            // patch_id != current is treated as a miss even before a bulk bust runs.
            if (patchScoped && currentPatchId != null
                    && e.patchId() != null && !currentPatchId.equals(e.patchId())) {
                return null;
            }
            return e;
        } catch (SQLException ex) {
            recordError("L2 read", path, ex);
            return null;
        }
    }

    /**
     * Conditionally store the body, branching on classification. PERMANENT keeps its v1 behaviour
     * (parse-gate matches, stamp {@code patch_id}, {@code expires_at = NULL}). TTL stores with
     * {@code expires_at = now + ttlFor(path)}, {@code patch_id = NULL}, and no parse-gate (parse-gating
     * is matches/PERMANENT only). Counts and eviction follow.
     */
    private void maybeStore(String path, String body, Classification c,
                            boolean patchScoped, String currentPatchId) {
        if (body == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Classification storeClass;
        Long expiresAt;
        String patchId;

        if (c == Classification.TTL) {
            // TTL: never parse-gated (parse-gating is matches-only) and never patch-scoped, so the
            // expires_at is the only difference from a PERMANENT store.
            long ttlMs = client.ttlFor(path).toMillis();
            if (ttlMs <= 0) {
                // ttlFor mapped this path to a non-positive horizon (no TTL-classified path does today,
                // but a future taxonomy edit might): nothing durable to store. Benign no-op miss —
                // already counted l2Miss in get(); do NOT count l2Store.
                return;
            }
            storeClass = Classification.TTL;
            expiresAt = now + ttlMs;
            patchId = null;
        } else {
            // PERMANENT — unchanged from v1.
            if (patchScoped && currentPatchId == null) {
                // The patch id is unknown (a transient patch-check failure). Storing a patch-scoped row
                // with patch_id=NULL would make it survive every future patchBust() (which spares NULL
                // match rows) AND the per-row stale guard (which skips NULL patch_ids) — permanent stale
                // data. Skip the store; the next request re-fetches once the patch id resolves.
                return;
            }
            boolean isMatch = MATCH_ID.matcher(stripQuery(path)).matches();
            if (isMatch && !isParsedMatch(body)) {
                // Unparsed match: do NOT store permanently — re-fetch next time (spec §5.1).
                l2StoreSkippedUnparsed.incrementAndGet();
                return;
            }
            storeClass = Classification.PERMANENT;
            expiresAt = null;   // PERMANENT: never expires by time.
            patchId = patchScoped ? currentPatchId : null;
        }

        // Collapse a burst of concurrent single-flight misses for the same path into ONE write: the
        // upstream call was already shared, so re-INSERTing the identical row (and bumping l2Store)
        // once per caller is pure churn on the serialized write connection. Applies to both classes.
        if (!storing.add(path)) {
            return;
        }
        try {
            store.put(path, body, storeClass, now, expiresAt, L2Store.SCHEMA_VERSION, patchId);
            l2Store.incrementAndGet();
            enforceCaps();
        } catch (SQLException ex) {
            recordError("L2 store", path, ex);
        } finally {
            storing.remove(path);
        }
    }

    /** Bring the store within both caps, atomically inside the store monitor (best-effort, spec §6.4). */
    private void enforceCaps() {
        try {
            // Delegate to the store so the check-and-evict happens under one lock; doing it here across
            // separate rowCount()/totalBodyBytes()/evictOldest() calls let concurrent writers each see
            // the same overage and over-evict (N x the surplus) under the virtual-thread executor.
            store.enforceCaps(config.maxRows(), config.maxBytes());
        } catch (SQLException ex) {
            recordError("L2 eviction", "(cap)", ex);
        }
    }

    /**
     * Lazy patch check (spec §5.2): at most once per {@link L2Config#patchCheckMillis}. Discovers the
     * current patch id (operator override, else the latest entry from {@code /constants/patch} via the
     * normal client path), busts stale static rows if it changed, and returns the current id (for the
     * caller's per-row guard / store stamping). Best-effort: any failure leaves the previous state.
     */
    private String maybeCheckPatch() {
        long now = System.currentTimeMillis();
        long last = lastPatchCheckMillis;
        boolean due = last == 0L || (now - last) >= config.patchCheckMillis();
        if (due && patchCheckInProgress.compareAndSet(false, true)) {
            try {
                String observed = observeCurrentPatch();
                String stored = store.storedPatchId();
                if (observed != null) {
                    if (!observed.equals(stored)) {
                        int deleted = store.patchBust();
                        store.storePatchId(observed);
                        if (deleted > 0) {
                            l2PatchBust.incrementAndGet();
                        }
                    }
                    // Only record the timestamp on a successful observe. A transient failure
                    // (observed == null) must leave the timer untouched so the next request
                    // retries, rather than suppressing checks for the full patchCheckMillis window.
                    lastPatchCheckMillis = now;
                }
                return observed != null ? observed : stored;
            } catch (SQLException ex) {
                recordError("L2 patch check", "/constants/patch", ex);
            } finally {
                patchCheckInProgress.set(false);
            }
        }
        // Not due (or another thread is checking): fall back to the stored id for the read guard.
        try {
            return store.storedPatchId();
        } catch (SQLException ex) {
            recordError("L2 patch read", "(meta)", ex);
            return null;
        }
    }

    /**
     * The current patch id: the operator override if set, else the largest {@code id} in
     * {@code /constants/patch} (fetched through the normal client path so it is L1-cached and
     * rate-limited). Returns {@code null} if it cannot be determined (leaves state unchanged).
     */
    private String observeCurrentPatch() {
        String override = config.patchIdOverride();
        if (override != null) {
            return override;
        }
        try {
            String body = client.getJson("/constants/patch");
            return latestPatchId(body);
        } catch (OpenDotaException ex) {
            // A failed patch fetch is non-fatal; we just don't bust this cycle.
            LOG.log(Level.FINE, ex, () -> "L2 patch check could not fetch /constants/patch");
            return null;
        }
    }

    /**
     * Extract the largest {@code "id"} value from the {@code /constants/patch} body. The endpoint
     * returns an array of patch objects each with a numeric {@code id}; the highest is the current
     * patch. Dep-free: a regex scan, not a JSON parse. Returns {@code null} if none found.
     */
    public static String latestPatchId(String body) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher m = PATCH_ID_NUM.matcher(body);
        long max = Long.MIN_VALUE;
        boolean found = false;
        while (m.find()) {
            try {
                long v = Long.parseLong(m.group(1));
                if (v > max) {
                    max = v;
                    found = true;
                }
            } catch (NumberFormatException ignored) {
                // skip a value too large to parse
            }
        }
        return found ? Long.toString(max) : null;
    }

    /**
     * Parse-gate signal (spec §5.1): a {@code /matches/{id}} body is FULLY PARSED when its {@code "version"}
     * is a number <em>and</em> at least one genuinely parse-only field corroborates it. The corroboration
     * matters because the probes are unanchored substring scans: it must be a signal that is actually FALSE
     * on unparsed bodies, so a stray numeric {@code "version"} substring alone can't pin an unparsed match
     * PERMANENT. Verified against a live unparsed match: {@code version} is absent, {@code od_data} is present
     * but {@code has_parsed:false}, and {@code objectives}/{@code purchase_log} are absent — hence we require
     * {@code has_parsed:true} or a non-null {@code objectives}/{@code purchase_log}, never bare {@code od_data}.
     */
    public static boolean isParsedMatch(String body) {
        if (body == null) {
            return false;
        }
        if (!PARSED_VERSION.matcher(body).find()) {
            return false;
        }
        // has_parsed is a value-EQUALITY probe (must equal true), NOT a non-null check: routing it through
        // hasNonNullValue would wrongly accept "has_parsed":false (the unparsed case). Keep it inline.
        return HAS_PARSED_TRUE.matcher(body).find()
                || hasNonNullValue(body, OBJECTIVES_KEY)
                || hasNonNullValue(body, PURCHASE_LOG_KEY);
    }

    /**
     * True if {@code body} contains a key (matched by the precompiled {@code keyColon} pattern, which
     * matches {@code "key"\s*:\s*}) whose value is not the literal {@code null}. A cheap guard so a
     * field present but explicitly null does not count.
     */
    private static boolean hasNonNullValue(String body, Pattern keyColon) {
        java.util.regex.Matcher m = keyColon.matcher(body);
        while (m.find()) {
            int valueStart = m.end();
            if (valueStart >= body.length()) {
                return false;
            }
            // If the value is literally null, keep scanning for another occurrence; otherwise it's non-null.
            if (!body.startsWith("null", valueStart)) {
                return true;
            }
        }
        return false;
    }

    /** Classify a path into its L2 durability class (spec §4 decision table, most specific first). */
    public static Classification classify(String path) {
        if (path == null) {
            return Classification.NO_STORE;
        }
        String p = stripQuery(path);

        // /matches/{id} — digits only, no further '/' — is PERMANENT (parse-gated). Any other
        // /matches/... shape falls to the default (NO_STORE) so a future endpoint isn't mis-stored.
        if (p.startsWith("/matches/")) {
            return MATCH_ID.matcher(p).matches() ? Classification.PERMANENT : Classification.NO_STORE;
        }
        // Static reference data — PERMANENT (patch-scoped).
        if (p.startsWith("/heroStats")) {
            return Classification.PERMANENT;
        }
        // Only the bare /heroes list is patch-scoped static data. The /heroes/{id}/* sub-endpoints
        // (matches, matchups, durations, players, itemPopularity) are rolling aggregates over recent
        // matches, so classify them TTL — never PERMANENT — so they aren't pinned across a patch.
        if (p.equals("/heroes")) {
            return Classification.PERMANENT;
        }
        if (p.startsWith("/heroes/")) {
            return Classification.TTL;
        }
        if (p.startsWith("/constants/")) {
            return Classification.PERMANENT;
        }
        // Volatile feeds — TTL (durably stored with expires_at = now + ttlFor(path)).
        if (p.startsWith("/schema")) {
            // The /explorer SQL schema is near-static but not patch-scoped, so TTL (24h horizon via
            // ttlFor) rather than PERMANENT — durable across restarts without pinning a stale schema.
            return Classification.TTL;
        }
        if (p.startsWith("/players/")) {
            return Classification.TTL;
        }
        if (p.startsWith("/proMatches")) {
            return Classification.TTL;
        }
        if (p.startsWith("/publicMatches")) {
            return Classification.TTL;
        }
        // Pro roster / team / league data is slow-moving but not patch-scoped, so TTL (durable until
        // its ttlFor horizon) rather than PERMANENT — same reasoning as /schema.
        if (p.startsWith("/proPlayers") || p.startsWith("/teams") || p.startsWith("/leagues")) {
            return Classification.TTL;
        }
        if (p.startsWith("/rankings")) {
            return Classification.TTL;
        }
        if (p.startsWith("/search")) {
            return Classification.TTL;
        }
        // Rolling aggregates over recent matches / a drifting player-base histogram — volatile, so
        // TTL (stored durably until their ttlFor horizon, not patch-pinned), matching the
        // /heroes/{id}/* treatment. Classified explicitly rather than via the NO_STORE default so
        // they aren't mistaken for uncacheable like /live.
        if (p.startsWith("/benchmarks")) {
            return Classification.TTL;
        }
        if (p.startsWith("/distributions")) {
            return Classification.TTL;
        }
        // Slow-moving record leaderboards, the rolling parse feed, and site metadata — TTL (durable
        // until their ttlFor horizon). /health is intentionally NOT here: it falls to NO_STORE below.
        if (p.startsWith("/records") || p.startsWith("/parsedMatches") || p.startsWith("/metadata")) {
            return Classification.TTL;
        }
        // Ad-hoc SQL: unique per query and 200s even on a SQL error — never store. (Explicit rather
        // than via the default below so a future reorder can't accidentally make it cacheable.)
        if (p.startsWith("/explorer")) {
            return Classification.NO_STORE;
        }
        // /live and everything unrecognised — NO_STORE.
        return Classification.NO_STORE;
    }

    /** Whether a PERMANENT path is patch-scoped static data (vs an immutable match). */
    private static boolean isPatchScoped(String path) {
        String p = stripQuery(path);
        return p.startsWith("/heroStats") || p.equals("/heroes") || p.startsWith("/constants/");
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private void recordError(String op, String path, SQLException ex) {
        l2Error.incrementAndGet();
        LOG.log(Level.WARNING, ex, () -> op + " failed for " + path + " (L2 degraded to passthrough): "
                + ex.getClass().getSimpleName());
    }

    /** Immutable snapshot of the L2 counters for a {@code /stats} endpoint (spec §8). */
    public record L2Stats(long l2Hit, long l2Miss, long l2Store, long l2StoreSkippedUnparsed,
                          long l2PatchBust, long l2Error, long noStore) {
    }

    /** Snapshot the current counters. */
    public L2Stats stats() {
        return new L2Stats(l2Hit.get(), l2Miss.get(), l2Store.get(), l2StoreSkippedUnparsed.get(),
                l2PatchBust.get(), l2Error.get(), noStore.get());
    }

    /** Close the SQLite store and the wrapped client (mirrors the server→client close chain). */
    @Override
    public void close() {
        try {
            store.close();
        } finally {
            client.close();
        }
    }
}
