package com.raorbit.opendota.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

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
            store.put("/matches/1", "{\"version\":1,\"od_data\":{}}", Classification.PERMANENT,
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
    void metaPatchIdRoundTrips(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("l2.db");
        try (L2Store store = new L2Store(db, L2Store.SCHEMA_VERSION)) {
            assertThat(store.storedPatchId()).isNull();
            store.storePatchId("54");
            assertThat(store.storedPatchId()).isEqualTo("54");
        }
    }
}
