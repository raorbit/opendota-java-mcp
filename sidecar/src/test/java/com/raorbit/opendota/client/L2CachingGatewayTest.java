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

    /**
     * A counting {@link OpenDotaClient} that serves canned bodies per path from memory (no HTTP), so
     * a test can assert exactly how many times the gateway delegated upstream. It bypasses all real
     * transport by overriding {@link #getJson}.
     */
    static final class CountingClient extends OpenDotaClient {
        final Map<String, String> bodies = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<String> requested = new ArrayList<>();
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
    }

    private static L2Config config(Path db) {
        return new L2Config(db, 50_000, 512L * 1024 * 1024, Duration.ofHours(6).toMillis(), null);
    }

    private static L2Config config(Path db, int maxRows, long maxBytes, String patchOverride) {
        return new L2Config(db, maxRows, maxBytes, Duration.ofHours(6).toMillis(), patchOverride);
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
            // The concurrent misses also collapse to ~one L2 write (not one per caller): the in-flight
            // store-dedup (the gateway's `storing` set) collapses only TRULY concurrent stores. After the
            // shared upstream call completes, the leader may finish its store and clear `storing` before a
            // straggling follower returns and stores again, so 1 or 2 writes can land — a benign race, not
            // flakiness. <=2 is therefore the strongest bound that holds deterministically; do not tighten
            // it to ==1 (that would reintroduce timing dependence), but it is still far below the n callers
            // an undeduped path would produce.
            assertThat(gw.stats().l2Store()).isLessThanOrEqualTo(2);
        } finally {
            upstream.stop(0);
        }
    }
}
