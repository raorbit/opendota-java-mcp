package com.raorbit.opendota.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link L2Store}: pragmas, the hand-rolled schema_version drop+rebuild migration
 * (Gate 6, spec §10/§6.3), INSERT OR REPLACE, eviction, and patch-bust at the store level.
 */
class L2StoreTest {

    // ---- Gate 6: schema_version mismatch rebuilds ----
    @Test
    void schemaVersionMismatchDropsAndRebuilds(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // Open at version 1, insert a row.
        try (L2Store store = new L2Store(db, 1)) {
            store.put("/matches/1", "{\"version\":1,\"od_data\":{\"has_parsed\":true}}", Classification.PERMANENT,
                    System.currentTimeMillis(), null, 1, null);
            assertThat(store.rowCount()).isEqualTo(1);
        }
        // Reopen with a bumped schema version: cache_entries is dropped + recreated, old row gone.
        try (L2Store store = new L2Store(db, 2)) {
            assertThat(store.rowCount()).isZero();
            assertThat(store.get("/matches/1")).isNull();
            // The new schema_version stamp is persisted.
            assertThat(store.readMeta("schema_version")).isEqualTo("2");
        }
        // Reopen again at the same (bumped) version: the row stays gone, table preserved as-is.
        try (L2Store store = new L2Store(db, 2)) {
            store.put("/heroes", "[]", Classification.PERMANENT, System.currentTimeMillis(), null, 2, "A");
        }
        try (L2Store store = new L2Store(db, 2)) {
            assertThat(store.rowCount()).isEqualTo(1);   // not rebuilt when the stamp matches
        }
    }

    @Test
    void walPragmaIsApplied(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        try (L2Store ignored = new L2Store(db, L2Store.SCHEMA_VERSION);
             Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
        }
    }

    @Test
    void insertOrReplaceOverwritesOnPathPrimaryKey(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            store.put("/matches/1", "first", Classification.PERMANENT, 100, null, L2Store.SCHEMA_VERSION, null);
            store.put("/matches/1", "second", Classification.PERMANENT, 200, null, L2Store.SCHEMA_VERSION, null);
            assertThat(store.rowCount()).isEqualTo(1);
            assertThat(store.get("/matches/1").body()).isEqualTo("second");
        }
    }

    @Test
    void evictOldestRemovesByStoredAtAscending(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            store.put("/a", "x", Classification.PERMANENT, 100, null, L2Store.SCHEMA_VERSION, null);
            store.put("/b", "x", Classification.PERMANENT, 200, null, L2Store.SCHEMA_VERSION, null);
            store.put("/c", "x", Classification.PERMANENT, 300, null, L2Store.SCHEMA_VERSION, null);
            assertThat(store.evictOldest(2)).isEqualTo(2);
            assertThat(store.get("/a")).isNull();
            assertThat(store.get("/b")).isNull();
            assertThat(store.get("/c")).isNotNull();
        }
    }

    @Test
    void byteTotalTracksUtf8BytesConsistentlyAndZeroesAfterEviction(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            // A body with a 4-byte emoji and a 3-byte CJK char: UTF-8 bytes != UTF-16 length != chars,
            // so a unit mismatch between put (Java) and evict (SQLite) would surface here.
            String body = "{\"n\":\"😀漢\"}";
            long expected = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            store.put("/matches/1", body, Classification.PERMANENT, 100, null, L2Store.SCHEMA_VERSION, null);
            assertThat(store.totalBodyBytes()).isEqualTo(expected);

            // Overwrite with a shorter ASCII body: the running total nets the delta exactly.
            store.put("/matches/1", "{}", Classification.PERMANENT, 200, null, L2Store.SCHEMA_VERSION, null);
            assertThat(store.totalBodyBytes()).isEqualTo(2L);

            // Eviction subtracts the same bytes it deleted (DELETE ... RETURNING), so the total is 0.
            assertThat(store.evictOldest(1)).isEqualTo(1);
            assertThat(store.totalBodyBytes()).isZero();
            assertThat(store.rowCount()).isZero();
        }
    }

    @Test
    void patchBustDeletesPatchScopedStaticButNotMatches(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            store.put("/heroes", "[]", Classification.PERMANENT, 100, null, L2Store.SCHEMA_VERSION, "A");
            store.put("/constants/items", "{}", Classification.PERMANENT, 110, null, L2Store.SCHEMA_VERSION, "A");
            store.put("/matches/1", "{}", Classification.PERMANENT, 120, null, L2Store.SCHEMA_VERSION, null);
            int deleted = store.patchBust();
            assertThat(deleted).isEqualTo(2);
            assertThat(store.get("/heroes")).isNull();
            assertThat(store.get("/constants/items")).isNull();
            assertThat(store.get("/matches/1")).as("match rows survive a patch bust").isNotNull();
        }
    }

    @Test
    void enforceCapsEvictsExpiredTtlBeforeOlderValidRows(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            long t0 = 1_000_000L;
            // An OLDER PERMANENT match (no expiry) and a NEWER TTL row that expires at t0+10.
            store.put("/matches/1", "{\"a\":1}", Classification.PERMANENT, t0, null, L2Store.SCHEMA_VERSION, null);
            store.put("/players/5", "{\"bb\":2}", Classification.TTL, t0 + 5, t0 + 10, L2Store.SCHEMA_VERSION, null);
            assertThat(store.rowCount()).isEqualTo(2);

            // Generous caps (no cap pressure): with `now` past the TTL, only the dead row is reclaimed,
            // and it must NOT take the older-but-valid PERMANENT row with it (the stored_at-ASC bug).
            int evicted = store.enforceCaps(1000, 1L << 30, t0 + 100);

            assertThat(evicted).isEqualTo(1);
            assertThat(store.rowCount()).isEqualTo(1);
            assertThat(store.get("/matches/1")).as("older valid PERMANENT row survives").isNotNull();
            assertThat(store.totalBodyBytes())
                    .isEqualTo("{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
    }

    @Test
    void enforceCapsKeepsTtlRowsThatHaveNotYetExpired(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            long t0 = 1_000_000L;
            store.put("/players/5", "{\"b\":2}", Classification.TTL, t0, t0 + 1000, L2Store.SCHEMA_VERSION, null);
            // `now` is before the expiry — the row is still live and must not be evicted.
            assertThat(store.enforceCaps(1000, 1L << 30, t0 + 100)).isZero();
            assertThat(store.rowCount()).isEqualTo(1);
        }
    }

    @Test
    void metaPatchIdRoundTrips(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            assertThat(store.storedPatchId()).isNull();
            store.storePatchId("54");
            assertThat(store.storedPatchId()).isEqualTo("54");
        }
    }

    @Test
    void initCountersReseedsUtf8ByteTotalAcrossReopenForNonAscii(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        // A body with a 4-byte emoji and a 3-byte CJK char: UTF-8 bytes != UTF-16 length != char count,
        // so seeding from char length on reopen (rather than SUM(LENGTH(CAST(body AS BLOB)))) would diverge.
        String body = "{\"n\":\"😀漢\"}";
        long expected = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            store.put("/matches/1", body, Classification.PERMANENT, 100, null, L2Store.SCHEMA_VERSION, null);
            assertThat(store.totalBodyBytes()).isEqualTo(expected);
        }
        // Reopen over the same file: initCounters re-seeds the byte total in UTF-8 bytes, not char count.
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            assertThat(store.totalBodyBytes()).isEqualTo(expected);
            assertThat(store.rowCount()).isEqualTo(1);
        }
    }

    @Test
    void zeroRowEvictionAndPatchBustReturnZeroAndLeaveTotalsUnchanged(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            // Empty table: evicting from nothing is a no-op and the totals stay zero.
            assertThat(store.evictOldest(5)).isZero();
            assertThat(store.totalBodyBytes()).isZero();
            assertThat(store.rowCount()).isZero();

            // A match row (patch_id = null) is not patch-scoped, so patchBust deletes nothing and it survives.
            store.put("/matches/1", "{}", Classification.PERMANENT, 100, null, L2Store.SCHEMA_VERSION, null);
            long bytesBefore = store.totalBodyBytes();
            long rowsBefore = store.rowCount();
            assertThat(store.patchBust()).isZero();
            assertThat(store.totalBodyBytes()).isEqualTo(bytesBefore);
            assertThat(store.rowCount()).isEqualTo(rowsBefore);
            assertThat(store.get("/matches/1")).as("a non-patch-scoped row survives a bust with no matches").isNotNull();
        }
    }

    // ---- read-connection pool (spec §7.1) ----

    @Test
    void fileBackedStoreOpensTheConfiguredReadPoolClampedToBounds(@TempDir Path tmp) throws Exception {
        try (L2Store store = new L2Store(tmp.resolve("n.db"), L2Store.SCHEMA_VERSION, 3)) {
            assertThat(store.readPoolCapacity()).isEqualTo(3);
        }
        // Over-large requests are clamped to MAX_READ_POOL; non-positive clamps up to 1.
        try (L2Store store = new L2Store(tmp.resolve("big.db"), L2Store.SCHEMA_VERSION, 9999)) {
            assertThat(store.readPoolCapacity()).isEqualTo(L2Store.MAX_READ_POOL);
        }
        try (L2Store store = new L2Store(tmp.resolve("zero.db"), L2Store.SCHEMA_VERSION, 0)) {
            assertThat(store.readPoolCapacity()).isEqualTo(1);
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a ':memory:' path has an illegal colon on Windows")
    void inMemoryStoreHasNoReadPoolAndRoundTrips() throws Exception {
        // The in-memory branch shares the single connection (a 2nd :memory: connection is a separate
        // empty db), so there is no read pool. Exercised on Linux CI; skipped on Windows (colon path).
        try (L2Store store = new L2Store(Path.of(":memory:"), L2Store.SCHEMA_VERSION)) {
            assertThat(store.readPoolCapacity()).isZero();
            store.put("/heroes", "[1,2]", Classification.PERMANENT, 100, null, L2Store.SCHEMA_VERSION, "A");
            assertThat(store.get("/heroes").body()).isEqualTo("[1,2]");
            assertThat(store.evictOldest(1)).isEqualTo(1);
            assertThat(store.get("/heroes")).isNull();
        }
    }

    @Test
    void writeOnWriteConnectionIsVisibleToAPooledReadConnection(@TempDir Path tmp) throws Exception {
        // get() borrows a *separate* read connection, so this verifies WAL cross-connection visibility
        // of a just-committed write on the same live store (not merely across a reopen).
        try (L2Store store = new L2Store(tmp.resolve("l2.db"), L2Store.SCHEMA_VERSION, 4)) {
            store.put("/heroes", "[1]", Classification.PERMANENT, 100, null, L2Store.SCHEMA_VERSION, "A");
            assertThat(store.get("/heroes")).isNotNull();
            assertThat(store.get("/heroes").body()).isEqualTo("[1]");
        }
    }

    @Test
    void readPoolGrantsNConcurrentLeasesThenBlocksUntilOneIsReturned(@TempDir Path tmp) throws Exception {
        int n = 3;
        try (L2Store store = new L2Store(tmp.resolve("l2.db"), L2Store.SCHEMA_VERSION, n)) {
            List<Connection> held = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Connection c = store.borrowReadForTest(1000);
                assertThat(c).as("lease %d", i).isNotNull();
                held.add(c);
            }
            // Pool exhausted: a further borrow times out fast and yields null — the best-effort miss path.
            assertThat(store.borrowReadForTest(100)).isNull();
            // Return one and a borrow succeeds again.
            store.returnReadForTest(held.remove(0));
            Connection again = store.borrowReadForTest(1000);
            assertThat(again).isNotNull();
            held.add(again);
            held.forEach(store::returnReadForTest);
        }
    }

    @Test
    void readPoolConnectionsRejectWritesViaQueryOnlyPragma(@TempDir Path tmp) throws Exception {
        try (L2Store store = new L2Store(tmp.resolve("l2.db"), L2Store.SCHEMA_VERSION, 2)) {
            Connection c = store.borrowReadForTest(1000);
            assertThat(c).isNotNull();
            try (Statement st = c.createStatement()) {
                // applyReadPragmas sets PRAGMA query_only = true, so a write on a read connection is rejected.
                assertThatThrownBy(() -> st.executeUpdate("DELETE FROM cache_entries"))
                        .isInstanceOf(SQLException.class);
            } finally {
                store.returnReadForTest(c);
            }
        }
    }

    @Test
    void concurrentReadsDuringWritesAllSeeCommittedData(@TempDir Path tmp) throws Exception {
        int readers = 8;
        try (L2Store store = new L2Store(tmp.resolve("l2.db"), L2Store.SCHEMA_VERSION, readers)) {
            store.put("/heroes", "[1]", Classification.PERMANENT, 100, null, L2Store.SCHEMA_VERSION, "A");
            CyclicBarrier start = new CyclicBarrier(readers + 1);
            AtomicInteger hits = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ExecutorService threads = Executors.newFixedThreadPool(readers);
            try {
                for (int i = 0; i < readers; i++) {
                    threads.submit(() -> {
                        try {
                            start.await();
                            for (int r = 0; r < 50; r++) {
                                L2Store.Entry e = store.get("/heroes");
                                if (e != null && "[1]".equals(e.body())) {
                                    hits.incrementAndGet();
                                }
                            }
                        } catch (Throwable t) {
                            failure.compareAndSet(null, t);
                        }
                    });
                }
                start.await();   // release all readers together
                // Hammer the write connection concurrently; the row always exists (overwrite, never delete).
                for (int w = 0; w < 50; w++) {
                    store.put("/heroes", "[1]", Classification.PERMANENT, 100 + w, null, L2Store.SCHEMA_VERSION, "A");
                }
            } finally {
                threads.shutdown();
                assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            // Reads run in parallel against live writes with no error, every read sees the committed row.
            assertThat(failure.get()).isNull();
            assertThat(hits.get()).isEqualTo(readers * 50);
        }
    }
}
