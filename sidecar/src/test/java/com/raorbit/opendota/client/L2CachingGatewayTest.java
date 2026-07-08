package com.raorbit.opendota.client;

import com.raorbit.opendota.sidecar.L2CachingGateway;
import com.raorbit.opendota.sidecar.L2Config;
import com.raorbit.opendota.sidecar.L2Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gate tests for the durable L2 cache tier (see {@code docs/l2-cache-design.md} §10).
 *
 * <p>Lives in the {@code client} package so the counting fake can extend {@link OpenDotaClient} via
 * its package-private base-URL constructor (the same trick the existing client-package tests use to
 * reach non-public surface).
 */
class L2CachingGatewayTest {

    // ---- realistic-ish bodies ----
    // A REAL unparsed OpenDota match has "version" ABSENT (not null) and od_data present as
    // {"has_parsed":false,...} — the shape verified live for the parse-gate (see L2CachingGateway
    // §5.1). No "version":<digit> appears, so PARSED_VERSION never matches and this stays UNPARSED.
    private static final String UNPARSED_MATCH =
            "{\"match_id\":111,\"radiant_win\":true,"
                    + "\"od_data\":{\"has_api\":true,\"has_parsed\":false},\"players\":[{}]}";
    private static final String PARSED_MATCH =
            "{\"match_id\":111,\"version\":21,\"radiant_win\":true,"
                    + "\"od_data\":{\"has_parsed\":true},\"objectives\":[{\"type\":\"tower\"}]}";
    private static final String HEROES_BODY = "[{\"id\":1,\"name\":\"npc_dota_hero_antimage\"}]";
    private static final String ITEMS_BODY = "{\"blink\":{\"id\":1}}";

    // ---- watched-player bodies (a /matches/{id} body carrying players[].account_id) ----
    /** The watched account_id this test set targets. */
    private static final long WATCHED_ID = 12345L;
    /** A PARSED match mentioning the watched account_id (note the surrounding non-watched ids/decoys). */
    private static final String WATCHED_MATCH_PARSED =
            "{\"match_id\":777,\"version\":21,\"od_data\":{\"has_parsed\":true},"
                    + "\"objectives\":[{\"type\":\"tower\"}],"
                    + "\"players\":[{\"account_id\":999},{\"account_id\":12345}]}";
    /** An UNPARSED match mentioning the watched account_id (version absent, has_parsed false). */
    private static final String WATCHED_MATCH_UNPARSED =
            "{\"match_id\":777,\"od_data\":{\"has_api\":true,\"has_parsed\":false},"
                    + "\"players\":[{\"account_id\":999},{\"account_id\":12345}]}";
    /**
     * A PARSED match whose ONLY occurrence of the watched id is a decoy: a longer id that contains it as
     * a prefix ({@code 123456789}) and a different key ({@code leaderboard_account_id}). The
     * {@code "account_id"} key + {@code (?![0-9])} boundary must reject both, so this is NOT pinned.
     */
    private static final String DECOY_MATCH_PARSED =
            "{\"match_id\":778,\"version\":21,\"od_data\":{\"has_parsed\":true},"
                    + "\"objectives\":[{\"type\":\"tower\"}],"
                    + "\"players\":[{\"account_id\":123456789,\"leaderboard_account_id\":12345}]}";

    private static L2Config.Watched watching(long... ids) {
        java.util.LinkedHashSet<Long> set = new java.util.LinkedHashSet<>();
        for (long id : ids) {
            set.add(id);
        }
        return new L2Config.Watched(set, 0, 0);
    }

    /**
     * A counting {@link OpenDotaClient} that serves canned bodies per path from memory (no HTTP), so
     * a test can assert exactly how many times the gateway delegated upstream. It bypasses all real
     * transport by overriding {@link #getJson}.
     */
    static final class CountingClient extends OpenDotaClient {
        final Map<String, String> bodies = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<String> requested = new ArrayList<>();
        /** Paths POSTed (parse requests), in order, so a test can assert auto-parse behaviour. */
        final List<String> posts = new CopyOnWriteArrayList<>();
        /** Optional gate: when set, getJson blocks on it (to force concurrent in-flight overlap). */
        volatile CountDownLatch gate;

        CountingClient() {
            super(null, "http://127.0.0.1:1/api");   // base URL never used; getJson is overridden
        }

        CountingClient with(String path, String body) {
            bodies.put(path, body);
            return this;
        }

        @Override
        public String getJson(String path) throws OpenDotaException {
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            calls.incrementAndGet();
            synchronized (requested) {
                requested.add(path);
            }
            String body = bodies.get(path);
            if (body == null) {
                throw new OpenDotaException(404, path, "{\"error\":\"not found\"}");
            }
            return body;
        }

        @Override
        public String postJson(String path) {
            // Record the parse request; never touch real transport.
            posts.add(path);
            return "{\"job\":{\"jobId\":1}}";
        }
    }

    private static L2Config config(Path db) {
        return new L2Config(db, 50_000, 512L * 1024 * 1024, Duration.ofHours(6).toMillis(), null);
    }

    private static L2Config config(Path db, int maxRows, long maxBytes, String patchOverride) {
        return new L2Config(db, maxRows, maxBytes, Duration.ofHours(6).toMillis(), patchOverride);
    }

    private static L2Config config(Path db, L2Config.Watched watched) {
        // watchedRefetchMillis = 0 → re-fetch an unparsed PINNED row on every access (the simplest,
        // deterministic behaviour for most watched tests); the backoff is exercised separately below.
        return config(db, watched, 0L);
    }

    private static L2Config config(Path db, L2Config.Watched watched, long watchedRefetchMillis) {
        // Auto-parse OFF by default for the watched tests, so they don't issue parse requests; the
        // auto-parse behaviour is exercised in its own tests via the overload below.
        return config(db, watched, watchedRefetchMillis, false);
    }

    private static L2Config config(Path db, L2Config.Watched watched, long watchedRefetchMillis,
                                   boolean autoParse) {
        // Read-pool size 4 mirrors L2Store.DEFAULT_READ_POOL (package-private, not visible here). Poll
        // cadence is irrelevant to these tests (the poll thread is never started here).
        return new L2Config(db, 50_000, 512L * 1024 * 1024, Duration.ofHours(6).toMillis(), null, 4, watched,
                watchedRefetchMillis, autoParse, Duration.ofHours(1).toMillis());
    }

    /**
     * Wait (up to ~2s) for the gateway's async access-driven auto-parse to issue at least {@code want}
     * parse requests. The POST now runs on {@link com.raorbit.opendota.sidecar.WatchedAutoParser}'s daemon
     * executor (so a GET never blocks on it), so the W8 positive assertions must await it rather than read
     * the counter the instant gw.get() returns.
     */
    private static void awaitParseRequested(L2CachingGateway gw, long want) throws InterruptedException {
        for (int i = 0; i < 200 && gw.stats().parseRequested() < want; i++) {
            Thread.sleep(10);
        }
    }

    private static long countRows(Path db) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM cache_entries")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    // ---- Gate 1: unparsed match is NOT stored permanent ----
    @Test
    void unparsedMatchIsNotStoredPermanent(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/111", UNPARSED_MATCH);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {

            assertThat(L2CachingGateway.classify("/matches/111")).isEqualTo(
                    com.raorbit.opendota.sidecar.Classification.PERMANENT);
            assertThat(gw.get("/matches/111")).isEqualTo(UNPARSED_MATCH);

            // Not stored, and the skip counter ticked.
            assertThat(store.get("/matches/111")).isNull();
            assertThat(gw.stats().l2StoreSkippedUnparsed()).isEqualTo(1);

            // A second request re-fetches (no L2 hit).
            assertThat(gw.get("/matches/111")).isEqualTo(UNPARSED_MATCH);
            assertThat(client.calls.get()).isEqualTo(2);
            assertThat(gw.stats().l2Hit()).isZero();
        }
    }

    // ---- Gate 2: parsed match IS stored permanent and survives a simulated restart ----
    @Test
    void parsedMatchIsStoredAndSurvivesRestart(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/111", PARSED_MATCH);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {
            assertThat(gw.get("/matches/111")).isEqualTo(PARSED_MATCH);
            assertThat(gw.stats().l2Store()).isEqualTo(1);
        }
        assertThat(client.calls.get()).isEqualTo(1);

        // New gateway + new store over the SAME db file: a fresh client with NO canned body, so any
        // delegation would 404 — the served body must come from L2.
        CountingClient freshClient = new CountingClient();   // empty; would throw if delegated to
        try (L2Store store2 = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw2 = new L2CachingGateway(freshClient, store2, config(db))) {
            assertThat(gw2.get("/matches/111")).isEqualTo(PARSED_MATCH);
            assertThat(freshClient.calls.get()).isZero();
            assertThat(gw2.stats().l2Hit()).isEqualTo(1);
        }
    }

    // ---- Gate 3: PERMANENT survives TTL expiry / never expires by time ----
    @Test
    void permanentNeverExpiresByTime(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/111", PARSED_MATCH);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {
            gw.get("/matches/111");
            // Confirm the stored row has a NULL expires_at (exempt from TTL expiry by construction).
            L2Store.Entry e = store.get("/matches/111");
            assertThat(e).isNotNull();
            assertThat(e.expiresAt()).isNull();

            // Backdate stored_at far into the past; it must still be served (no time expiry).
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE cache_entries SET stored_at = 1 WHERE path = '/matches/111'");
            }
            CountingClient freshClient = new CountingClient();   // would 404 if delegated
            try (L2Store store2 = new L2Store(db, L2Store.SCHEMA_VERSION);
                 L2CachingGateway gw2 = new L2CachingGateway(freshClient, store2, config(db))) {
                assertThat(gw2.get("/matches/111")).isEqualTo(PARSED_MATCH);
                assertThat(freshClient.calls.get()).isZero();
            }
        }
    }

    // ---- Gate 4: patch bump invalidates static caches but not matches ----
    @Test
    void patchBumpInvalidatesStaticNotMatches(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // Patch A: store /heroes, /constants/items, and a parsed match.
        CountingClient client = new CountingClient()
                .with("/heroes", HEROES_BODY)
                .with("/constants/items", ITEMS_BODY)
                .with("/matches/111", PARSED_MATCH);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, 50_000, 512L * 1024 * 1024, "A"))) {
            gw.get("/heroes");
            gw.get("/constants/items");
            gw.get("/matches/111");
            assertThat(countRows(db)).isEqualTo(3);
        }

        // Patch B: a new gateway whose observed patch is B -> the lazy check busts static rows.
        CountingClient clientB = new CountingClient()
                .with("/heroes", HEROES_BODY)
                .with("/constants/items", ITEMS_BODY)
                .with("/matches/111", PARSED_MATCH);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(clientB, store, config(db, 50_000, 512L * 1024 * 1024, "B"))) {
            // First patch-scoped request triggers the check+bust.
            gw.get("/heroes");
            // Static rows for the OLD patch were evicted; the match row remains.
            assertThat(store.get("/matches/111")).as("match survives patch bump").isNotNull();
            // /constants/items (patch A) is gone -> a fresh request re-fetches it.
            assertThat(gw.stats().l2PatchBust()).isEqualTo(1);
        }
    }

    // ---- Gate 4b: a body fetched right after a patch bust is NOT stored under the new patch id ----
    @Test
    void postBustFetchIsNotStoredWhileL1MayStillServePrePatchBody(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // Patch A: /constants/items is stored, stamped A.
        CountingClient clientA = new CountingClient().with("/constants/items", ITEMS_BODY);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(clientA, store, config(db, 50_000, 512L * 1024 * 1024, "A"))) {
            gw.get("/constants/items");
            assertThat(store.get("/constants/items")).isNotNull();
        }

        // Patch B: the bust clears L2, but the wrapped client's L1 TtlCache (simulated here by the
        // canned body, which still holds the PRE-patch items) keeps answering for up to its 6h TTL.
        // Storing that answer would stamp patch-A data with patch id B and pin it for the whole
        // cycle — the store must be skipped until the L1 horizon has elapsed since the bust.
        CountingClient clientB = new CountingClient().with("/constants/items", ITEMS_BODY);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(clientB, store, config(db, 50_000, 512L * 1024 * 1024, "B"))) {
            // Triggers the check+bust, misses L2, fetches the (possibly pre-patch) body — served, not stored.
            assertThat(gw.get("/constants/items")).isEqualTo(ITEMS_BODY);
            assertThat(gw.stats().l2PatchBust()).isEqualTo(1);
            assertThat(store.get("/constants/items"))
                    .as("no patch-scoped store within ttlFor(/constants/) of the bust")
                    .isNull();

            // A NON-patch-scoped path is unaffected by the hold-off window.
            clientB.bodies.put(BENCHMARKS, BENCHMARKS_BODY);
            gw.get(BENCHMARKS);
            assertThat(store.get(BENCHMARKS)).isNotNull();
        }
    }

    // ---- Transient patch-check failure backs off instead of re-fetching every request ----
    @Test
    void transientPatchCheckFailureBacksOffInsteadOfRefetchingEveryRequest(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // No patch override, and /constants/patch is deliberately NOT stubbed, so observeCurrentPatch()
        // 404s -> null (a transient upstream failure). Only /heroes is served.
        CountingClient client = new CountingClient().with("/heroes", HEROES_BODY);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {
            // Three patch-scoped requests in quick succession, well within the failure backoff window.
            gw.get("/heroes");
            gw.get("/heroes");
            gw.get("/heroes");
            // The patch fetch was attempted exactly ONCE, not once per request: after the first failure the
            // gateway stamps a short backoff rather than re-fetching /constants/patch on every request
            // (which, during a real outage, would burn a rate-limiter permit each time).
            long patchFetches = client.requested.stream().filter("/constants/patch"::equals).count();
            assertThat(patchFetches).isEqualTo(1);
        }
    }

    // ---- Gate 5: per-row stale patch_id is treated as a miss ----
    @Test
    void perRowStalePatchIdIsTreatedAsMiss(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // Hand-insert a /heroes row stamped with patch "OLD" and meta patch_id "OLD", then read with
        // a gateway whose override says current patch is "NEW". The bulk bust will also run, but we
        // assert the read-time per-row guard: even before/independent of the bust the stale row is a miss.
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            store.put("/heroes", HEROES_BODY, com.raorbit.opendota.sidecar.Classification.PERMANENT,
                    System.currentTimeMillis(), null, L2Store.SCHEMA_VERSION, "OLD");
            store.storePatchId("OLD");
        }
        CountingClient client = new CountingClient().with("/heroes", "[]");   // distinct fresh body
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, 50_000, 512L * 1024 * 1024, "NEW"))) {
            // The stale /heroes (patch OLD) must NOT be served; the gateway re-fetches.
            assertThat(gw.get("/heroes")).isEqualTo("[]");
            assertThat(client.calls.get()).isEqualTo(1);
        }
    }

    // ---- Gate 7: NO_STORE is pure passthrough ----
    @Test
    void noStorePathsArePurePassthrough(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient()
                .with("/live", "[{\"match_id\":1}]")
                .with("/totally-unknown", "{\"x\":1}");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {

            assertThat(gw.get("/live")).isEqualTo("[{\"match_id\":1}]");
            assertThat(gw.get("/totally-unknown")).isEqualTo("{\"x\":1}");

            // The client is always called; no row appears for either path.
            assertThat(client.calls.get()).isEqualTo(2);
            assertThat(store.get("/live")).isNull();
            assertThat(store.get("/totally-unknown")).isNull();
            assertThat(store.rowCount()).isZero();

            // A repeat call still goes upstream (never an L2 hit).
            gw.get("/live");
            assertThat(client.calls.get()).isEqualTo(3);
            assertThat(gw.stats().l2Hit()).isZero();
            assertThat(gw.stats().l2Store()).isZero();
        }
    }

    // ==== v2 durable-TTL gate tests (docs/l2-cache-design.md §7.2) ====

    private static final String BENCHMARKS = "/benchmarks?hero_id=1";
    private static final String BENCHMARKS_BODY = "{\"result\":{\"gold_per_min\":[]}}";
    private static final long BENCHMARKS_TTL_MS = Duration.ofHours(1).toMillis();   // ttlFor(/benchmarks)
    private static final String DISTRIBUTIONS = "/distributions";
    private static final String DISTRIBUTIONS_BODY = "{\"ranks\":{\"rows\":[]}}";
    private static final long DISTRIBUTIONS_TTL_MS = Duration.ofHours(6).toMillis();   // ttlFor(/distributions)

    /**
     * Read a single column for {@code path} directly from the db file (bypassing the read predicates).
     * {@code col} is a trusted test-literal column name; {@code path} is bound as a parameter.
     */
    private static Long longCol(Path db, String col, String path) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + col + " FROM cache_entries WHERE path = ?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private static String strCol(Path db, String col, String path) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + col + " FROM cache_entries WHERE path = ?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---- v2.1: a TTL path is stored with a non-null expires_at == stored_at + ttlFor ----
    @Test
    void ttlPathIsStoredWithExpiry(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with(BENCHMARKS, BENCHMARKS_BODY);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {
            assertThat(L2CachingGateway.classify(BENCHMARKS)).isEqualTo(
                    com.raorbit.opendota.sidecar.Classification.TTL);

            assertThat(gw.get(BENCHMARKS)).isEqualTo(BENCHMARKS_BODY);

            L2Store.Entry e = store.get(BENCHMARKS);
            assertThat(e).isNotNull();
            assertThat(e.classification()).isEqualTo("TTL");
            assertThat(e.expiresAt()).isNotNull();
            assertThat(e.patchId()).isNull();
            // expires_at - stored_at is exactly ttlFor(path) (one `now` used for both, no drift).
            assertThat(e.expiresAt() - e.storedAt()).isEqualTo(BENCHMARKS_TTL_MS);

            // l2Store ticked, noStore did NOT (only NO_STORE counts noStore now).
            assertThat(gw.stats().l2Store()).isEqualTo(1);
            assertThat(gw.stats().l2Miss()).isEqualTo(1);
            assertThat(gw.stats().noStore()).isZero();
        }
    }

    // ---- v2.2: a non-expired TTL row is served from L2 across a simulated restart ----
    @Test
    void ttlRowServedFromL2AcrossRestartBeforeExpiry(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // Use the 6h-TTL /distributions so real wall-clock test time stays well inside the window.
        CountingClient client = new CountingClient().with(DISTRIBUTIONS, DISTRIBUTIONS_BODY);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {
            assertThat(gw.get(DISTRIBUTIONS)).isEqualTo(DISTRIBUTIONS_BODY);
            assertThat(gw.stats().l2Store()).isEqualTo(1);
        }
        assertThat(client.calls.get()).isEqualTo(1);

        // New gateway + new store over the SAME db file, with a fresh client that has NO canned body
        // (any delegation would 404). The served body must come from L2 — cross-restart warmth.
        CountingClient freshClient = new CountingClient();
        try (L2Store store2 = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw2 = new L2CachingGateway(freshClient, store2, config(db))) {
            assertThat(gw2.get(DISTRIBUTIONS)).isEqualTo(DISTRIBUTIONS_BODY);
            assertThat(freshClient.calls.get()).isZero();
            assertThat(gw2.stats().l2Hit()).isEqualTo(1);
        }
    }

    // ---- v2.3: an expired TTL row is a miss, re-fetched, and re-stored in place (no duplicate row) ----
    @Test
    void expiredTtlRowIsMissedReFetchedAndReStoredInPlace(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // Hand-insert an ALREADY-EXPIRED /benchmarks row (expires_at in the past) so lazy-expiry fires
        // deterministically without touching real time.
        long past = System.currentTimeMillis() - 1;
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            store.put(BENCHMARKS, "{\"stale\":true}", com.raorbit.opendota.sidecar.Classification.TTL,
                    past - BENCHMARKS_TTL_MS, past, L2Store.SCHEMA_VERSION, null);
            assertThat(store.rowCount()).isEqualTo(1);
        }

        CountingClient client = new CountingClient().with(BENCHMARKS, BENCHMARKS_BODY);   // fresh body
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {
            // The expired row is NOT served: lookup returns null (lazy-expiry), so we re-fetch upstream.
            assertThat(gw.get(BENCHMARKS)).isEqualTo(BENCHMARKS_BODY);
            assertThat(client.calls.get()).isEqualTo(1);
            assertThat(gw.stats().l2Miss()).isEqualTo(1);
            assertThat(gw.stats().l2Store()).isEqualTo(1);

            // INSERT OR REPLACE on the path PK: still exactly one row, now with the fresh body and a
            // future expires_at.
            assertThat(store.rowCount()).isEqualTo(1);
            L2Store.Entry e = store.get(BENCHMARKS);
            assertThat(e).isNotNull();
            assertThat(e.body()).isEqualTo(BENCHMARKS_BODY);
            assertThat(e.expiresAt()).isGreaterThan(System.currentTimeMillis());
        }
    }

    // ---- v2.4: TTL rows are evicted under the row cap (class-agnostic LRU) ----
    @Test
    void ttlRowsEvictedUnderCap(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient()
                .with("/benchmarks?hero_id=1", "{\"r\":1}")
                .with("/benchmarks?hero_id=2", "{\"r\":2}")
                .with("/benchmarks?hero_id=3", "{\"r\":3}")
                .with("/benchmarks?hero_id=4", "{\"r\":4}")
                .with("/benchmarks?hero_id=5", "{\"r\":5}");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, 3, 512L * 1024 * 1024, null))) {
            for (int i = 1; i <= 5; i++) {
                gw.get("/benchmarks?hero_id=" + i);
                // Exceed a coarse system-clock granularity (legacy Windows timer ~15.6ms) so each
                // store gets a strictly-increasing stored_at and the LRU eviction order is deterministic.
                Thread.sleep(25);
            }
            assertThat(store.rowCount()).isLessThanOrEqualTo(3);
            // The oldest TTL rows (1, 2) were evicted regardless of class; the newest survive.
            assertThat(store.get("/benchmarks?hero_id=1")).isNull();
            assertThat(store.get("/benchmarks?hero_id=2")).isNull();
            assertThat(store.get("/benchmarks?hero_id=5")).isNotNull();
        }
    }

    // ---- v2.5: re-storing a TTL path refreshes (advances) its expires_at, never stacks a row ----
    @Test
    void reStoreRefreshesTtlExpiry(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // Seed an already-expired row at a known-old stored_at so we can prove expires_at advanced.
        long oldStored = 1_000L;
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            store.put(BENCHMARKS, "{\"old\":true}", com.raorbit.opendota.sidecar.Classification.TTL,
                    oldStored, oldStored + BENCHMARKS_TTL_MS, L2Store.SCHEMA_VERSION, null);
        }
        Long oldExpires = longCol(db, "expires_at", BENCHMARKS);
        assertThat(oldExpires).isEqualTo(oldStored + BENCHMARKS_TTL_MS);

        long before = System.currentTimeMillis();
        CountingClient client = new CountingClient().with(BENCHMARKS, BENCHMARKS_BODY);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {
            gw.get(BENCHMARKS);   // expired -> miss -> re-fetch -> re-store (INSERT OR REPLACE)
            assertThat(store.rowCount()).isEqualTo(1);   // overwritten, not stacked
            Long newExpires = longCol(db, "expires_at", BENCHMARKS);
            assertThat(newExpires).isNotNull();
            // The refreshed expires_at is at least `before + ttl` (advanced far past the old value).
            assertThat(newExpires).isGreaterThanOrEqualTo(before + BENCHMARKS_TTL_MS);
            assertThat(newExpires).isGreaterThan(oldExpires);
            assertThat(strCol(db, "patch_id", BENCHMARKS)).isNull();
        }
    }

    // ---- v2.6 (regression): noStore counts ONLY NO_STORE now (no longer TTL) ----
    @Test
    void noStoreCountsOnlyNoStoreNotTtl(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient()
                .with("/live", "[{\"match_id\":1}]")
                .with(BENCHMARKS, BENCHMARKS_BODY);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {
            gw.get("/live");        // NO_STORE -> noStore++
            gw.get(BENCHMARKS);     // TTL -> l2Miss++/l2Store++, NOT noStore
            assertThat(gw.stats().noStore()).isEqualTo(1);
            assertThat(gw.stats().l2Store()).isEqualTo(1);
            assertThat(gw.stats().l2Miss()).isEqualTo(1);
        }
    }

    // ---- v2.7 (invariant): every classify()-TTL prefix has a POSITIVE ttlFor horizon ----
    // classify() (the durability class) and ttlFor() (the horizon) are independent tables in different
    // classes. A TTL-classified path whose ttlFor is non-positive would be silently dropped by
    // maybeStore's defensive ttlMs<=0 skip (stored as nothing, never durable). This enumerates the TTL
    // set and pins the seam, so a future taxonomy edit that adds a TTL prefix without a positive ttlFor
    // entry fails here at build time rather than silently degrading to a non-durable path.
    @Test
    void everyTtlClassifiedPathHasPositiveTtlForHorizon() throws Exception {
        String[] ttlPaths = {
                "/players/123", "/proMatches", "/publicMatches", "/rankings",
                "/search?q=x", "/benchmarks?hero_id=1", "/distributions", "/heroes/14/matches",
                "/schema", "/proPlayers", "/topPlayers", "/teams/15", "/teams/15/matches",
                "/teams/15/players", "/leagues/4210", "/leagues/4210/matches", "/leagues/4210/teams",
                "/records/kills", "/parsedMatches", "/metadata",
        };
        try (OpenDotaClient client = new CountingClient()) {
            for (String path : ttlPaths) {
                assertThat(L2CachingGateway.classify(path))
                        .as("classify(%s)", path)
                        .isEqualTo(com.raorbit.opendota.sidecar.Classification.TTL);
                assertThat(client.ttlFor(path).toMillis())
                        .as("ttlFor(%s) must be positive so the TTL row is actually stored durably", path)
                        .isPositive();
            }
        }
    }

    // ---- M1: ad-hoc SQL /explorer is never stored (200s even on a SQL error; unique per query) ----
    @Test
    void explorerIsNoStore() {
        assertThat(L2CachingGateway.classify("/explorer?sql=SELECT+1"))
                .isEqualTo(com.raorbit.opendota.sidecar.Classification.NO_STORE);
    }

    // ---- M5: a parse job's polled status is volatile — never stored ----
    @Test
    void requestStatusIsNoStore() {
        assertThat(L2CachingGateway.classify("/request/42"))
                .isEqualTo(com.raorbit.opendota.sidecar.Classification.NO_STORE);
    }

    // ---- Gate 9: cap eviction ----
    @Test
    void capEvictionKeepsCountWithinLimit(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient()
                .with("/matches/1", "{\"match_id\":1,\"version\":1,\"od_data\":{\"has_parsed\":true}}")
                .with("/matches/2", "{\"match_id\":2,\"version\":1,\"od_data\":{\"has_parsed\":true}}")
                .with("/matches/3", "{\"match_id\":3,\"version\":1,\"od_data\":{\"has_parsed\":true}}")
                .with("/matches/4", "{\"match_id\":4,\"version\":1,\"od_data\":{\"has_parsed\":true}}")
                .with("/matches/5", "{\"match_id\":5,\"version\":1,\"od_data\":{\"has_parsed\":true}}");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, 3, 512L * 1024 * 1024, null))) {
            for (int i = 1; i <= 5; i++) {
                gw.get("/matches/" + i);
                // Exceed a coarse system-clock granularity (legacy Windows timer ~15.6ms) so each
                // store gets a strictly-increasing stored_at and the LRU eviction order is deterministic.
                Thread.sleep(25);
            }
            assertThat(store.rowCount()).isLessThanOrEqualTo(3);
            // The oldest (1, 2) were evicted; the newest survive.
            assertThat(store.get("/matches/1")).isNull();
            assertThat(store.get("/matches/2")).isNull();
            assertThat(store.get("/matches/5")).isNotNull();
        }
    }

    // ---- Gate 9b: a body larger than the WHOLE byte budget is skipped, never allowed to wipe the tier ----
    @Test
    void oversizedBodyIsSkippedNotAllowedToWipeTheTier(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        String small = "{\"r\":1}";
        String huge = "{\"rows\":\"" + "x".repeat(300) + "\"}";   // > the whole 256-byte budget below
        CountingClient client = new CountingClient()
                .with("/benchmarks?hero_id=1", small)
                .with("/benchmarks?hero_id=2", huge);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, 50_000, 256L, null))) {
            gw.get("/benchmarks?hero_id=1");
            assertThat(store.get("/benchmarks?hero_id=1")).isNotNull();

            // Without the guard, storing the huge (newest) row would drive the oldest-first byte-cap
            // loop through every OTHER row and then the huge row itself — an empty tier. The body is
            // still served; it just isn't stored.
            assertThat(gw.get("/benchmarks?hero_id=2")).isEqualTo(huge);
            assertThat(store.get("/benchmarks?hero_id=2")).as("oversized body never stored").isNull();
            assertThat(store.get("/benchmarks?hero_id=1")).as("the rest of the tier survives").isNotNull();
        }
    }

    // ---- W7b: same guard for the watched archive (a single huge match must not zero the archive) ----
    @Test
    void oversizedWatchedBodyIsSkippedNotAllowedToWipeTheArchive(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        String smallWatched = watchedParsed(701);
        String hugeWatched = "{\"match_id\":702,\"version\":21,\"od_data\":{\"has_parsed\":true},"
                + "\"objectives\":[{\"type\":\"tower\"}],\"players\":[{\"account_id\":12345,\"log\":\""
                + "x".repeat(600) + "\"}]}";   // > the whole 400-byte watched budget below
        CountingClient client = new CountingClient()
                .with("/matches/701", smallWatched)
                .with("/matches/702", hugeWatched);
        L2Config.Watched watched = new L2Config.Watched(java.util.Set.of(WATCHED_ID), 0, 400);
        L2Config cfg = new L2Config(db, 50_000, 512L * 1024 * 1024, Duration.ofHours(6).toMillis(), null, 4, watched);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, cfg)) {
            gw.get("/matches/701");
            assertThat(store.get("/matches/701")).isNotNull();

            assertThat(gw.get("/matches/702")).isEqualTo(hugeWatched);
            assertThat(store.get("/matches/702")).as("oversized watched body never stored").isNull();
            assertThat(store.get("/matches/701")).as("the archive survives").isNotNull();
            assertThat(gw.stats().pinnedRows()).isEqualTo(1);
        }
    }

    // ---- Gate 10: SQLite error is non-fatal ----
    @Test
    void sqliteErrorIsNonFatalAndDegradesToPassthrough(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/111", PARSED_MATCH);
        L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
        L2CachingGateway gw = new L2CachingGateway(client, store, config(db));
        try {
            // Close the store underneath the gateway: every subsequent L2 read/store throws SQLException.
            store.close();

            // The request still returns the upstream body (L2 degrades to passthrough), never throws.
            assertThatCode(() -> assertThat(gw.get("/matches/111")).isEqualTo(PARSED_MATCH))
                    .doesNotThrowAnyException();
            assertThat(client.calls.get()).isEqualTo(1);
            assertThat(gw.stats().l2Error()).isGreaterThanOrEqualTo(1);
        } finally {
            gw.close();
        }
    }

    // ---- Gate 11: single-flight preserved across L2 misses ----
    @Test
    void singleFlightPreservedAcrossL2Misses(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // A real single-flight client over a fake upstream, so the gateway's misses funnel into the
        // client's inFlight de-dup. /matches/{id} is cacheable in ttlFor (60s), so followers await.
        com.sun.net.httpserver.HttpServer upstream =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newCachedThreadPool());
        AtomicInteger upstreamHits = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        // Tripped when the single-flight leader actually reaches upstream, so the main thread can
        // release deterministically (instead of sleeping) the instant the one upstream call is parked.
        CountDownLatch leaderInUpstream = new CountDownLatch(1);
        upstream.createContext("/api/matches/111", ex -> {
            upstreamHits.incrementAndGet();
            leaderInUpstream.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            byte[] b = PARSED_MATCH.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (var os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        upstream.start();
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/api";
        OpenDotaClient realClient = new OpenDotaClient(null, base);

        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(realClient, store, config(db))) {
            int n = 8;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            // Line the callers up with a barrier (n callers + this thread) instead of a sleep: the
            // barrier trips only once every caller thread is actually scheduled and parked on it, so
            // they all enter gw.get() together regardless of how slowly the pool spins up.
            CyclicBarrier start = new CyclicBarrier(n + 1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return gw.get("/matches/111");
                }));
            }
            start.await(5, TimeUnit.SECONDS);   // release all n callers simultaneously
            // Release the upstream only once the single-flight leader has provably reached it (and is
            // therefore registered in the client's inFlight map). Until then no follower can win the
            // leader slot, so this guarantees exactly one upstream call without an arbitrary sleep.
            assertThat(leaderInUpstream.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<String> f : futures) {
                assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo(PARSED_MATCH);
            }
            pool.shutdownNow();

            // Exactly one upstream call despite N concurrent L2 misses, and a single stored row.
            assertThat(upstreamHits.get()).isEqualTo(1);
            assertThat(store.rowCount()).isEqualTo(1);
            // The concurrent misses also collapse the redundant L2 writes (the gateway's `storing`
            // in-flight set): an undeduped path would store once per caller (n), but the dedup collapses
            // the simultaneous stores so far fewer land. The exact count is timing-dependent — a straggler
            // can store after the leader clears `storing`, and a loaded CI box stretches that window — so
            // assert only the deterministic property (strictly fewer writes than callers), not a fragile
            // small constant. Correctness is already pinned by upstreamHits==1 and rowCount==1 above.
            assertThat(gw.stats().l2Store()).isLessThan(n);
        } finally {
            upstream.stop(0);
        }
    }

    // ==== watched-player archive (PINNED) gate tests (docs/l2-cache-design.md §6.5) ====

    // ---- W1: a watched UNPARSED match is stored PINNED immediately (save now, upgrade later) ----
    @Test
    void watchedUnparsedMatchIsStoredPinnedImmediately(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/777", WATCHED_MATCH_UNPARSED);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, watching(WATCHED_ID)))) {
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            // Stored despite being unparsed — bypassing the isParsedMatch skip — with classification PINNED.
            L2Store.Entry e = store.get("/matches/777");
            assertThat(e).isNotNull();
            assertThat(e.classification()).isEqualTo("PINNED");
            assertThat(e.expiresAt()).isNull();
            assertThat(gw.stats().l2WatchedStore()).isEqualTo(1);
            assertThat(gw.stats().l2StoreSkippedUnparsed()).as("watched bypasses the unparsed skip").isZero();
            assertThat(gw.stats().pinnedRows()).isEqualTo(1);
            assertThat(gw.stats().pinnedBytes())
                    .isEqualTo(WATCHED_MATCH_UNPARSED.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
    }

    // ---- W2: an unparsed PINNED row forces a re-fetch until parsed, then becomes an L2 hit ----
    @Test
    void unparsedPinnedForcesReFetchUntilParsedThenHits(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/777", WATCHED_MATCH_UNPARSED);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, watching(WATCHED_ID)))) {
            // 1st get: stored PINNED-unparsed.
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(client.calls.get()).isEqualTo(1);
            // 2nd get: lookup() forces a re-fetch (no L2 hit) because the stored PINNED row is unparsed.
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(client.calls.get()).isEqualTo(2);
            assertThat(gw.stats().l2Hit()).isZero();

            // OpenDota finishes parsing: upstream now returns the PARSED body. The next get re-fetches,
            // upgrades the PINNED row in place, and a subsequent get is an L2 hit.
            client.bodies.put("/matches/777", WATCHED_MATCH_PARSED);
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_PARSED);
            assertThat(client.calls.get()).isEqualTo(3);
            assertThat(store.rowCount()).isEqualTo(1);   // upgraded in place, not stacked
            // The durable body was actually replaced by the parsed one (not just any parsed-looking body).
            assertThat(store.get("/matches/777").body()).isEqualTo(WATCHED_MATCH_PARSED);

            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_PARSED);
            assertThat(client.calls.get()).as("parsed PINNED now serves from L2").isEqualTo(3);
            assertThat(gw.stats().l2Hit()).isEqualTo(1);
            L2Store.Entry e = store.get("/matches/777");
            assertThat(e.classification()).isEqualTo("PINNED");
        }
    }

    // ---- W2b: an upstream failure during the unparsed re-fetch serves the retained unparsed body ----
    @Test
    void unparsedPinnedReFetchFailureServesRetainedBody(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/777", WATCHED_MATCH_UNPARSED);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, watching(WATCHED_ID)))) {
            // 1st get archives the unparsed body PINNED.
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);

            // Upstream now fails (drop the canned body → getJson throws 404). The forced re-fetch of the
            // unparsed PINNED row fails, but the retained body is served rather than propagating the error.
            client.bodies.remove("/matches/777");
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(gw.stats().l2Hit()).as("served from the archive after the re-fetch failed").isEqualTo(1);

            // A non-archived path with no stored row still propagates the upstream error.
            assertThatThrownBy(() -> gw.get("/matches/999")).isInstanceOf(OpenDotaException.class);
        }
    }

    // ---- W2b2: the outage-serve advances the re-fetch stamp so the outage isn't re-paid per access ----
    @Test
    void outageServeAdvancesRefetchStampSoUpstreamIsNotHammered(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/777", WATCHED_MATCH_UNPARSED);
        long interval = Duration.ofHours(1).toMillis();
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, watching(WATCHED_ID), interval))) {
            // Archive the unparsed match, then expire its re-fetch stamp so the next access is "due".
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            L2Store.Entry stamped = store.get("/matches/777");
            store.put("/matches/777", WATCHED_MATCH_UNPARSED, com.raorbit.opendota.sidecar.Classification.PINNED,
                    stamped.storedAt(), 1L, L2Store.SCHEMA_VERSION, null);

            // Upstream goes down. The due re-fetch fails and the archive serves the retained body...
            client.bodies.remove("/matches/777");
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            int callsAfterFirstOutageServe = client.calls.get();
            // ...and the stamp was advanced, so accesses during the rest of the outage serve straight
            // from L2 instead of re-paying the full upstream attempt each time (the documented
            // at-most-once-per-interval bound must hold during an outage too).
            assertThat(store.get("/matches/777").expiresAt()).isGreaterThan(System.currentTimeMillis());
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(client.calls.get()).as("no further upstream attempts within the backoff window")
                    .isEqualTo(callsAfterFirstOutageServe);
        }
    }

    // ---- W2c: a positive re-fetch interval serves an unparsed pinned match from L2 between re-checks ----
    @Test
    void unparsedPinnedBacksOffBetweenReFetchesThenUpgradesWhenDue(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/777", WATCHED_MATCH_UNPARSED);
        long interval = Duration.ofHours(1).toMillis();
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, watching(WATCHED_ID), interval))) {
            // 1st get archives the unparsed body with a FUTURE re-fetch stamp.
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(client.calls.get()).isEqualTo(1);
            L2Store.Entry stamped = store.get("/matches/777");
            assertThat(stamped.expiresAt()).as("an unparsed pinned row carries a re-fetch stamp").isNotNull();

            // 2nd get is within the interval → served straight from L2, NO upstream re-fetch (the churn fix).
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(client.calls.get()).as("served from L2 within the re-fetch interval").isEqualTo(1);
            assertThat(gw.stats().l2Hit()).isEqualTo(1);

            // Simulate the interval elapsing by expiring the stamp; the next get re-fetches and, now that
            // OpenDota has parsed the replay, upgrades the row in place.
            store.put("/matches/777", WATCHED_MATCH_UNPARSED, com.raorbit.opendota.sidecar.Classification.PINNED,
                    stamped.storedAt(), 1L, L2Store.SCHEMA_VERSION, null);
            client.bodies.put("/matches/777", WATCHED_MATCH_PARSED);
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_PARSED);
            assertThat(client.calls.get()).as("re-fetched once the stamp elapsed").isEqualTo(2);
            assertThat(store.get("/matches/777").body()).isEqualTo(WATCHED_MATCH_PARSED);
            assertThat(store.get("/matches/777").expiresAt()).as("parsed pinned has no stamp").isNull();
        }
    }

    // ---- W2d: an unparsed pinned row whose player was un-watched is reclaimed on the next re-fetch ----
    @Test
    void unwatchedUnparsedPinnedRowIsReclaimed(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/777", WATCHED_MATCH_UNPARSED);
        // First run: the player IS watched, so the unparsed match is archived PINNED.
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, watching(WATCHED_ID)))) {
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(store.get("/matches/777").classification()).isEqualTo("PINNED");
            assertThat(gw.stats().pinnedRows()).isEqualTo(1);
        }
        // Second run over the SAME db with NO watched players (the operator un-watched the player). The
        // next access force-misses the orphan, re-fetches (still unparsed), sees it's no longer watched,
        // and reclaims it so it stops counting against the watched budget.
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, L2Config.Watched.NONE))) {
            assertThat(store.pinnedRowCount()).as("orphan is still present after the restart").isEqualTo(1);
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(store.get("/matches/777")).as("orphan reclaimed (deleted)").isNull();
            assertThat(store.pinnedRowCount()).isZero();
            assertThat(store.pinnedBodyBytes()).isZero();
        }
    }

    // ---- W2e: with a positive interval, an orphan is SERVED until its stamp elapses, then reclaimed ----
    @Test
    void unwatchedUnparsedPinnedRowIsServedUntilStampElapsesThenReclaimed(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/777", WATCHED_MATCH_UNPARSED);
        // First run: the player IS watched and the re-fetch interval is POSITIVE, so the archived unparsed
        // row carries a FUTURE expires_at re-fetch stamp (not a "due now" null stamp).
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store,
                     config(db, watching(WATCHED_ID), Duration.ofHours(1).toMillis()))) {
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            L2Store.Entry stamped = store.get("/matches/777");
            assertThat(stamped.classification()).isEqualTo("PINNED");
            assertThat(stamped.expiresAt()).as("a future re-fetch stamp is live").isNotNull();
            assertThat(stamped.expiresAt()).isGreaterThan(System.currentTimeMillis());
        }

        // Second run over the SAME db, now UN-WATCHED. While the stamp is still live, lookup() SERVES the
        // orphan straight from L2 (no force-miss, so the reclaim path does not run yet): the row stays put.
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, L2Config.Watched.NONE,
                     Duration.ofHours(1).toMillis()))) {
            assertThat(store.pinnedRowCount()).isEqualTo(1);
            assertThat(gw.get("/matches/777")).as("served from L2 while the stamp is live").isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(gw.stats().l2Hit()).isEqualTo(1);
            assertThat(store.get("/matches/777")).as("orphan still present (not reclaimed while served)").isNotNull();
            assertThat(store.pinnedRowCount()).as("still pinned while the stamp is live").isEqualTo(1);

            // Now expire the stamp (push expires_at into the past, keeping it PINNED/unparsed). The next
            // access force-misses, re-fetches (still unparsed, no longer watched), and reclaims the orphan.
            store.put("/matches/777", WATCHED_MATCH_UNPARSED,
                    com.raorbit.opendota.sidecar.Classification.PINNED, 100, 1L, L2Store.SCHEMA_VERSION, null);
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_UNPARSED);
            assertThat(store.get("/matches/777")).as("orphan reclaimed once the stamp elapsed").isNull();
            assertThat(store.pinnedRowCount()).isZero();
        }
    }

    // ---- W3: a parsed watched match serves straight from L2 on the 2nd get ----
    @Test
    void parsedWatchedMatchServesFromL2(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/777", WATCHED_MATCH_PARSED);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, watching(WATCHED_ID)))) {
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_PARSED);
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_PARSED);
            assertThat(client.calls.get()).isEqualTo(1);
            assertThat(gw.stats().l2Hit()).isEqualTo(1);
            assertThat(gw.stats().l2WatchedStore()).isEqualTo(1);
        }
    }

    // ---- W4: a NON-watched unparsed match is still skipped (the archive doesn't change v1 behaviour) ----
    @Test
    void nonWatchedUnparsedMatchStillSkipped(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // Watching a DIFFERENT id than the match mentions, so this match is ordinary PERMANENT-class.
        CountingClient client = new CountingClient().with("/matches/111", UNPARSED_MATCH);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, watching(999999L)))) {
            assertThat(gw.get("/matches/111")).isEqualTo(UNPARSED_MATCH);
            assertThat(store.get("/matches/111")).isNull();
            assertThat(gw.stats().l2StoreSkippedUnparsed()).isEqualTo(1);
            assertThat(gw.stats().l2WatchedStore()).isZero();
            assertThat(gw.stats().pinnedRows()).isZero();
        }
    }

    // ---- W5: no watched players -> the pattern is null and no row is ever PINNED ----
    @Test
    void noWatchedPlayersNeverPins(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // Even though this PARSED body mentions account_id 12345, an empty watch set pins nothing.
        CountingClient client = new CountingClient().with("/matches/777", WATCHED_MATCH_PARSED);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db))) {
            assertThat(gw.get("/matches/777")).isEqualTo(WATCHED_MATCH_PARSED);
            L2Store.Entry e = store.get("/matches/777");
            assertThat(e).isNotNull();
            assertThat(e.classification()).as("ordinary PERMANENT, not PINNED").isEqualTo("PERMANENT");
            assertThat(gw.stats().l2WatchedStore()).isZero();
            assertThat(gw.stats().pinnedRows()).isZero();
        }
    }

    // ---- W6: a decoy (substring id / leaderboard_account_id key) must NOT false-pin ----
    @Test
    void decoyAccountIdDoesNotFalseMatch(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/778", DECOY_MATCH_PARSED);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, config(db, watching(WATCHED_ID)))) {
            assertThat(gw.get("/matches/778")).isEqualTo(DECOY_MATCH_PARSED);
            L2Store.Entry e = store.get("/matches/778");
            assertThat(e).isNotNull();
            // 12345 appears only as a prefix of 123456789 and under leaderboard_account_id — neither
            // satisfies "account_id":12345(?![0-9]) — so the match is ordinary PERMANENT, not PINNED.
            assertThat(e.classification()).isEqualTo("PERMANENT");
            assertThat(gw.stats().l2WatchedStore()).isZero();
        }
    }

    // ---- W7: the watched row cap evicts the oldest pinned across stores while ordinary cache is kept ----
    @Test
    void watchedRowCapEvictsOldestPinnedNotOrdinary(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // Three distinct watched parsed matches + one ordinary parsed match; watchedMaxRows = 2.
        CountingClient client = new CountingClient()
                .with("/matches/701", watchedParsed(701))
                .with("/matches/702", watchedParsed(702))
                .with("/matches/703", watchedParsed(703))
                .with("/matches/800", "{\"match_id\":800,\"version\":1,\"od_data\":{\"has_parsed\":true}}");
        L2Config.Watched watched = new L2Config.Watched(java.util.Set.of(WATCHED_ID), 2, 0);
        L2Config cfg = new L2Config(db, 50_000, 512L * 1024 * 1024, Duration.ofHours(6).toMillis(), null, 4, watched);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store, cfg)) {
            gw.get("/matches/800");   // ordinary PERMANENT
            for (int id = 701; id <= 703; id++) {
                gw.get("/matches/" + id);
                Thread.sleep(25);   // strictly-increasing stored_at for deterministic LRU
            }
            // The watched budget (2) evicted the oldest pinned (701); the ordinary row is untouched.
            assertThat(store.pinnedRowCount()).isEqualTo(2);
            assertThat(store.get("/matches/701")).isNull();
            assertThat(store.get("/matches/703")).isNotNull();
            assertThat(store.get("/matches/800")).as("ordinary cache is governed by the main cap, not watched").isNotNull();
        }
    }

    // ---- W8: access-driven auto-parse requests a parse for an unparsed watched match (deduped) ----
    @Test
    void accessDrivenAutoParseRequestsParseForUnparsedWatchedMatchOnce(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        CountingClient client = new CountingClient().with("/matches/777", WATCHED_MATCH_UNPARSED);
        // Auto-parse ON; refetch interval 0 so every access force-misses (the dedup, not the stamp, must
        // prevent a second parse request).
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store,
                     config(db, watching(WATCHED_ID), 0L, true))) {
            gw.get("/matches/777");
            // The POST is async (fire-and-forget off the GET thread), so await it before asserting.
            awaitParseRequested(gw, 1);
            assertThat(client.posts).containsExactly("/request/777");
            assertThat(gw.stats().parseRequested()).isEqualTo(1);

            // A second fetch (still unparsed) re-stores but must NOT request a second parse — deduped.
            gw.get("/matches/777");
            assertThat(client.posts).as("parse requested at most once per match").containsExactly("/request/777");
            assertThat(gw.stats().parseRequested()).isEqualTo(1);
        }
    }

    @Test
    void accessDrivenAutoParseDoesNotFireForParsedOrNonWatchedOrWhenOff(@TempDir Path tmp) throws Exception {
        // (a) parsed watched match -> nothing to parse.
        Path a = tmp.resolve("a.db");
        CountingClient c1 = new CountingClient().with("/matches/777", WATCHED_MATCH_PARSED);
        try (L2Store store = new L2Store(a, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(c1, store, config(a, watching(WATCHED_ID), 0L, true))) {
            gw.get("/matches/777");
            assertThat(c1.posts).isEmpty();
            assertThat(gw.stats().parseRequested()).isZero();
        }
        // (b) unparsed NON-watched match -> not our player, no parse.
        Path b = tmp.resolve("b.db");
        CountingClient c2 = new CountingClient().with("/matches/111", UNPARSED_MATCH);
        try (L2Store store = new L2Store(b, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(c2, store, config(b, watching(999999L), 0L, true))) {
            gw.get("/matches/111");
            assertThat(c2.posts).isEmpty();
        }
        // (c) auto-parse OFF -> even an unparsed watched match is never parsed.
        Path d = tmp.resolve("d.db");
        CountingClient c3 = new CountingClient().with("/matches/777", WATCHED_MATCH_UNPARSED);
        try (L2Store store = new L2Store(d, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(c3, store, config(d, watching(WATCHED_ID), 0L, false))) {
            gw.get("/matches/777");
            assertThat(c3.posts).isEmpty();
            assertThat(gw.stats().parseRequested()).isZero();
        }
    }

    // ---- W9: a /matches/<huge-id> unparsed watched body must NOT throw and must NOT request a parse ----
    @Test
    void hugeMatchIdDoesNotThrowOrRequestParse(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // A 20+-digit match id overflows a long: matchIdOf must return -1L (not throw), and the call site
        // skips the parse request for a non-positive id. The path still classifies PERMANENT (digits only).
        String hugePath = "/matches/99999999999999999999";
        String body = "{\"match_id\":99999999999999999999,"
                + "\"od_data\":{\"has_api\":true,\"has_parsed\":false},"
                + "\"players\":[{\"account_id\":12345}]}";
        CountingClient client = new CountingClient().with(hugePath, body);
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION);
             L2CachingGateway gw = new L2CachingGateway(client, store,
                     config(db, watching(WATCHED_ID), 0L, true))) {
            assertThatCode(() -> assertThat(gw.get(hugePath)).isEqualTo(body)).doesNotThrowAnyException();
            // No parse request was issued for the unparseable id (and none could have raced in async).
            assertThat(client.posts).isEmpty();
            assertThat(gw.stats().parseRequested()).isZero();
        }
    }

    /** A parsed match body mentioning the watched account_id, parameterised by match id. */
    private static String watchedParsed(int matchId) {
        return "{\"match_id\":" + matchId + ",\"version\":21,\"od_data\":{\"has_parsed\":true},"
                + "\"objectives\":[{\"type\":\"tower\"}],\"players\":[{\"account_id\":12345}]}";
    }
}
