package com.raorbit.opendota.sidecar;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The durable L2 cache decorator (see {@code docs/l2-cache-design.md} §2, §3).
 *
 * <p>Wraps a single {@link OpenDotaClient}: on each {@link #get} it classifies the path, consults
 * the SQLite {@link L2Store} for PERMANENT hits, delegates misses to {@code client.getJson} (where
 * L1 + single-flight + the rate limiter still run, unchanged), parse-gates {@code /matches/{id}}
 * bodies, and conditionally stores the result. L2 is a best-effort accelerator: any
 * {@link SQLException} is counted, logged at warning, and never fails the request.
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
    private static final Pattern PARSED_VERSION = Pattern.compile("\"version\"\\s*:\\s*-?\\d");

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
        if (c == Classification.NO_STORE || c == Classification.TTL) {
            // v1: TTL is not stored; both NO_STORE and TTL are pure passthrough to the client,
            // where L1 + single-flight + the rate limiter still apply.
            noStore.incrementAndGet();
            return client.getJson(path);
        }

        // PERMANENT. For patch-scoped static data, run the lazy patch check first so a stale
        // generation is busted before we read it (and the per-row guard catches any survivor).
        boolean patchScoped = isPatchScoped(path);
        String currentPatchId = null;
        if (patchScoped) {
            currentPatchId = maybeCheckPatch();
        }

        // L2 read first.
        L2Store.Entry hit = lookup(path, patchScoped, currentPatchId);
        if (hit != null) {
            l2Hit.incrementAndGet();
            return hit.body();
        }

        // Miss: delegate to the client (L1 / single-flight / rate limiting happen here).
        l2Miss.incrementAndGet();
        String body = client.getJson(path);

        // Parse-gate + conditional store.
        maybeStore(path, body, patchScoped, currentPatchId);
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

    /** Parse-gate (matches) and conditionally store the body; counts and eviction follow. */
    private void maybeStore(String path, String body, boolean patchScoped, String currentPatchId) {
        if (body == null) {
            return;
        }
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
        Long expiresAt = null;   // PERMANENT: never expires by time.
        String patchId = patchScoped ? currentPatchId : null;
        try {
            store.put(path, body, Classification.PERMANENT, System.currentTimeMillis(),
                    expiresAt, L2Store.SCHEMA_VERSION, patchId);
            l2Store.incrementAndGet();
            enforceCaps();
        } catch (SQLException ex) {
            recordError("L2 store", path, ex);
        }
    }

    /** Delete oldest rows until within both the row-count and byte caps (best-effort, spec §6.4). */
    private void enforceCaps() {
        try {
            long rows = store.rowCount();
            int overRows = (int) Math.min(Integer.MAX_VALUE, Math.max(0, rows - config.maxRows()));
            if (overRows > 0) {
                store.evictOldest(overRows);
            }
            // Byte cap: evict oldest in small batches until under the byte budget (or empty).
            while (store.totalBodyBytes() > config.maxBytes() && store.rowCount() > 0) {
                int deleted = store.evictOldest(Math.max(1, (int) Math.min(64, store.rowCount())));
                if (deleted == 0) {
                    break;
                }
            }
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
        java.util.regex.Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
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
     * Parse-gate signal (spec §5.1): a {@code /matches/{id}} body is FULLY PARSED when the top-level
     * {@code "version"} is a non-null number <em>and</em> at least one corroborating parse field is
     * present (defence in depth). Cheap, dep-free string probes over the already size-capped body.
     */
    public static boolean isParsedMatch(String body) {
        if (body == null) {
            return false;
        }
        if (!PARSED_VERSION.matcher(body).find()) {
            return false;
        }
        // Corroborating signal: any one of these only appears on parsed matches.
        return hasNonNullKey(body, "od_data")
                || hasNonNullKey(body, "objectives")
                || body.contains("\"purchase_log\"");
    }

    /**
     * True if {@code body} contains a {@code "key"} whose value is not {@code null} (i.e. the next
     * non-whitespace token after the colon is not the literal {@code null}). A cheap guard so a
     * field present but explicitly null does not count.
     */
    private static boolean hasNonNullKey(String body, String key) {
        java.util.regex.Matcher m =
                Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*").matcher(body);
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
        if (p.startsWith("/heroes")) {
            return Classification.PERMANENT;
        }
        if (p.startsWith("/constants/")) {
            return Classification.PERMANENT;
        }
        // Volatile feeds — TTL (not stored in v1).
        if (p.startsWith("/players/")) {
            return Classification.TTL;
        }
        if (p.startsWith("/proMatches")) {
            return Classification.TTL;
        }
        if (p.startsWith("/publicMatches")) {
            return Classification.TTL;
        }
        if (p.startsWith("/rankings")) {
            return Classification.TTL;
        }
        if (p.startsWith("/search")) {
            return Classification.TTL;
        }
        // /live and everything unrecognised — NO_STORE.
        return Classification.NO_STORE;
    }

    /** Whether a PERMANENT path is patch-scoped static data (vs an immutable match). */
    private static boolean isPatchScoped(String path) {
        String p = stripQuery(path);
        return p.startsWith("/heroStats") || p.startsWith("/heroes") || p.startsWith("/constants/");
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
