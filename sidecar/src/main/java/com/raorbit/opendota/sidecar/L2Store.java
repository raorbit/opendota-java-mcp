package com.raorbit.opendota.sidecar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The durable, SQLite-backed L2 store (see {@code docs/l2-cache-design.md} §6, §7).
 *
 * <p>One {@code cache_entries} row per cached response, keyed by the OpenDota path (incl. query —
 * the same key L1 uses). On open it sets WAL + busy_timeout pragmas, runs a hand-rolled
 * drop+rebuild migration keyed on {@code schema_version}, and exposes a small CRUD surface the
 * {@link L2CachingGateway} drives.
 *
 * <p>Concurrency: a single JDBC connection. Writes ({@link #put}, eviction, {@link #patchBust},
 * rebuild) are serialized on the connection monitor; reads share the same connection (SQLite
 * serializes internally) and WAL lets readers proceed under a concurrent writer (spec §7.1).
 *
 * <p>Errors are <em>not</em> swallowed here — they surface as {@link SQLException} so the gateway
 * can decide policy (count an {@code l2Error} and fall back to passthrough; spec §7.2).
 */
public final class L2Store implements AutoCloseable {

    /** Bumped to force a destructive drop+rebuild of {@code cache_entries} (spec §6.3). */
    public static final int SCHEMA_VERSION = 1;

    private static final String META_SCHEMA_VERSION = "schema_version";
    private static final String META_PATCH_ID = "patch_id";

    private final Connection conn;
    /**
     * A separate read-only connection for the hot {@link #get} path, so an L2-hit read isn't serialized
     * behind a concurrent write (WAL lets a reader proceed while {@link #conn} writes). For an in-memory
     * db — where a second connection would be a private, empty db — this is the same object as {@code conn}.
     */
    private Connection readConn;
    /** Guards {@link #readConn}; equals {@code this} when readConn == conn so reads still serialize with writes. */
    private Object readLock;

    // O(1) running totals so enforceCaps() avoids full-table COUNT/SUM scans on the request path
    // (spec §6.4). Seeded once on open, then maintained by put/evictOldest/patchBust under the
    // connection monitor. Bytes are real UTF-8 bytes on every axis — Java getBytes(UTF_8).length on
    // insert, SQLite LENGTH(CAST(body AS BLOB)) on seed/evict — so the total can't drift on non-ASCII.
    private final AtomicLong currentRows = new AtomicLong();
    private final AtomicLong currentBytes = new AtomicLong();

    /**
     * Open (creating if necessary) a store at {@code dbPath} using the given schema version. The
     * parent directory is created if missing.
     *
     * @param dbPath        the SQLite file, or {@code ":memory:"} for an in-memory db
     * @param schemaVersion the current schema version; a stored mismatch triggers a rebuild
     */
    public L2Store(Path dbPath, int schemaVersion) throws SQLException {
        String url = toJdbcUrl(dbPath);
        boolean inMemory = ":memory:".equals(dbPath.toString());
        this.conn = DriverManager.getConnection(url);
        try {
            applyPragmas();
            migrate(schemaVersion);
            initCounters();
            if (inMemory) {
                // A second :memory: connection is a separate empty db, so share the one connection;
                // reads then serialize with writes (readLock == this), which is correct for one db.
                this.readConn = this.conn;
                this.readLock = this;
            } else {
                this.readConn = DriverManager.getConnection(url);
                applyReadPragmas();
                this.readLock = new Object();
            }
        } catch (SQLException e) {
            closeQuietly();
            throw e;
        }
    }

    private static String toJdbcUrl(Path dbPath) throws SQLException {
        String s = dbPath.toString();
        if (":memory:".equals(s)) {
            return "jdbc:sqlite::memory:";
        }
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new SQLException("cannot create L2 db directory " + parent + ": " + e.getMessage(), e);
            }
        }
        return "jdbc:sqlite:" + dbPath.toAbsolutePath();
    }

    private void applyPragmas() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA synchronous = NORMAL");
            st.execute("PRAGMA foreign_keys = ON");
        }
    }

    /** Read connection: a busy_timeout for brief WAL contention and query_only as a defensive guard. */
    private void applyReadPragmas() throws SQLException {
        try (Statement st = readConn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA query_only = true");
        }
    }

    /**
     * Hand-rolled migration (spec §6.3): fresh db ⇒ create + stamp; matching stamp ⇒ use as-is;
     * differing stamp ⇒ drop {@code cache_entries}, recreate, re-stamp. No Flyway.
     */
    private void migrate(int schemaVersion) throws SQLException {
        createMetaTable();
        Integer stored = readSchemaVersion();
        if (stored == null) {
            createCacheTable();
            writeMeta(META_SCHEMA_VERSION, Integer.toString(schemaVersion));
        } else if (stored != schemaVersion) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS cache_entries");
            }
            createCacheTable();
            writeMeta(META_SCHEMA_VERSION, Integer.toString(schemaVersion));
            // The patch row may be reset; clearing it just forces one harmless patch re-check.
            deleteMeta(META_PATCH_ID);
        } else {
            // Stamp matches; ensure the table exists (idempotent for an interrupted prior create).
            createCacheTable();
        }
    }

    private void createMetaTable() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS meta ("
                    + "key TEXT PRIMARY KEY, "
                    + "value TEXT)");
        }
    }

    private void createCacheTable() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS cache_entries ("
                    + "path TEXT PRIMARY KEY, "
                    + "body TEXT NOT NULL, "
                    + "classification TEXT NOT NULL, "
                    + "stored_at INTEGER NOT NULL, "
                    + "expires_at INTEGER, "
                    + "schema_version INTEGER NOT NULL, "
                    + "patch_id TEXT)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cache_entries_stored_at ON cache_entries (stored_at)");
        }
    }

    private Integer readSchemaVersion() throws SQLException {
        String v = readMeta(META_SCHEMA_VERSION);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            // A corrupt stamp is treated as a mismatch so it rebuilds (returns a value that
            // can never equal a real schema version).
            return Integer.MIN_VALUE;
        }
    }

    /** UTF-8 byte length of a body — matches SQLite {@code LENGTH(CAST(body AS BLOB))} and TtlCache. */
    private static long utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Seed the running row/byte totals from the table once, after migration. */
    private void initCounters() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*), COALESCE(SUM(LENGTH(CAST(body AS BLOB))), 0) FROM cache_entries")) {
            if (rs.next()) {
                currentRows.set(rs.getLong(1));
                currentBytes.set(rs.getLong(2));
            }
        }
    }

    /**
     * A row read from {@code cache_entries}. The gateway applies the read-time predicates
     * (TTL expiry, per-row stale {@code patch_id}, stale {@code schema_version}).
     */
    public record Entry(String path, String body, String classification, long storedAt,
                        Long expiresAt, int schemaVersion, String patchId) {
    }

    /** Read the row for {@code path}, or {@code null} if absent. Caller applies validity predicates. */
    public Entry get(String path) throws SQLException {
        // Reads use a dedicated connection under readLock (not the write monitor), so an L2-hit isn't
        // blocked behind a concurrent write; WAL lets the reader see the latest committed snapshot.
        synchronized (readLock) {
            String sql = "SELECT path, body, classification, stored_at, expires_at, schema_version, patch_id "
                    + "FROM cache_entries WHERE path = ?";
            try (PreparedStatement ps = readConn.prepareStatement(sql)) {
                ps.setString(1, path);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    long expires = rs.getLong("expires_at");
                    Long expiresAt = rs.wasNull() ? null : expires;
                    return new Entry(
                            rs.getString("path"),
                            rs.getString("body"),
                            rs.getString("classification"),
                            rs.getLong("stored_at"),
                            expiresAt,
                            rs.getInt("schema_version"),
                            rs.getString("patch_id"));
                }
            }
        }
    }

    /** Store (or overwrite) a row with {@code INSERT OR REPLACE} on the {@code path} primary key. */
    public synchronized void put(String path, String body, Classification classification, long storedAt,
                                 Long expiresAt, int schemaVersion, String patchId) throws SQLException {
        // INSERT OR REPLACE may overwrite an existing row, so net out the old body's bytes (and skip
        // the row increment) to keep the running totals exact across overwrites.
        long oldBytes = 0L;
        boolean existed = false;
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT LENGTH(CAST(body AS BLOB)) FROM cache_entries WHERE path = ?")) {
            sel.setString(1, path);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) {
                    existed = true;
                    oldBytes = rs.getLong(1);
                }
            }
        }
        String sql = "INSERT OR REPLACE INTO cache_entries"
                + "(path, body, classification, stored_at, expires_at, schema_version, patch_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, path);
            ps.setString(2, body);
            ps.setString(3, classification.name());
            ps.setLong(4, storedAt);
            if (expiresAt == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setLong(5, expiresAt);
            }
            ps.setInt(6, schemaVersion);
            if (patchId == null) {
                ps.setNull(7, java.sql.Types.VARCHAR);
            } else {
                ps.setString(7, patchId);
            }
            ps.executeUpdate();
        }
        if (!existed) {
            currentRows.incrementAndGet();
        }
        currentBytes.addAndGet(utf8Len(body) - oldBytes);
    }

    /** Current number of rows in {@code cache_entries} (O(1) running total, maintained on writes). */
    public long rowCount() {
        return currentRows.get();
    }

    /**
     * Approximate total body bytes across all rows (O(1) running total). Mirrors SQLite's char-based
     * {@code LENGTH()}; for cap purposes an approximation is fine (spec §6.4 calls the byte cap
     * "approximate") — it bounds growth without exactness.
     */
    public long totalBodyBytes() {
        return currentBytes.get();
    }

    /**
     * Evict the {@code n} oldest rows by {@code stored_at} ascending (LRU-ish, spec §6.4). PERMANENT
     * rows are <em>not</em> exempt — once over a cap the oldest go regardless of class.
     *
     * @return the number of rows actually deleted
     */
    public synchronized int evictOldest(int n) throws SQLException {
        if (n <= 0) {
            return 0;
        }
        // Delete the n oldest rows AND sum their byte sizes in one statement via RETURNING, so the
        // summed and deleted rows are guaranteed identical even when stored_at values tie (a separate
        // SUM subquery could break the LIMIT tie differently from the DELETE and skew the byte total).
        String sql = "DELETE FROM cache_entries WHERE path IN "
                + "(SELECT path FROM cache_entries ORDER BY stored_at ASC LIMIT ?) "
                + "RETURNING LENGTH(CAST(body AS BLOB))";
        int deleted = 0;
        long freedBytes = 0L;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    deleted++;
                    freedBytes += rs.getLong(1);
                }
            }
        }
        currentRows.addAndGet(-deleted);
        currentBytes.addAndGet(-freedBytes);
        return deleted;
    }

    /**
     * Bring the table within both the row-count and byte caps, evicting oldest-first, all under this
     * store's monitor so the check and the eviction are atomic — concurrent callers can't each read
     * the same overage and over-evict (N x the surplus). Best-effort (spec §6.4).
     *
     * @return the total number of rows evicted
     */
    public synchronized int enforceCaps(long maxRows, long maxBytes) throws SQLException {
        int total = 0;
        long overRows = currentRows.get() - maxRows;
        if (overRows > 0) {
            total += evictOldest((int) Math.min(Integer.MAX_VALUE, overRows));
        }
        // Byte cap: evict oldest in small batches until under the byte budget (or empty).
        while (currentBytes.get() > maxBytes && currentRows.get() > 0) {
            int deleted = evictOldest((int) Math.max(1L, Math.min(64L, currentRows.get())));
            if (deleted == 0) {
                break;
            }
            total += deleted;
        }
        return total;
    }

    /**
     * Patch-bust (spec §5.2): delete every PERMANENT row whose {@code patch_id IS NOT NULL} (the
     * patch-scoped static rows). Match rows have {@code patch_id = NULL} and survive.
     *
     * @return the number of rows deleted
     */
    public synchronized int patchBust() throws SQLException {
        // Single DELETE ... RETURNING so the deleted rows and their summed bytes are the same set.
        String sql = "DELETE FROM cache_entries WHERE classification = 'PERMANENT' AND patch_id IS NOT NULL "
                + "RETURNING LENGTH(CAST(body AS BLOB))";
        int deleted = 0;
        long freedBytes = 0L;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                deleted++;
                freedBytes += rs.getLong(1);
            }
        }
        currentRows.addAndGet(-deleted);
        currentBytes.addAndGet(-freedBytes);
        return deleted;
    }

    /** Read a {@code meta} value, or {@code null} if absent. */
    public synchronized String readMeta(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Upsert a {@code meta} key/value. */
    public synchronized void writeMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private void deleteMeta(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    /** Read the stored current patch id (the {@code meta.patch_id} bookkeeping row), or {@code null}. */
    public String storedPatchId() throws SQLException {
        return readMeta(META_PATCH_ID);
    }

    /** Record the observed current patch id. */
    public void storePatchId(String patchId) throws SQLException {
        writeMeta(META_PATCH_ID, patchId);
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        // Close the read connection too, unless it is the same object as the write connection (in-memory).
        if (readConn != null && readConn != conn) {
            closeOne(readConn);
        }
        closeOne(conn);
    }

    private static void closeOne(Connection c) {
        try {
            if (c != null && !c.isClosed()) {
                c.close();
            }
        } catch (SQLException ignored) {
            // best-effort close
        }
    }
}
