package com.raorbit.opendota.sidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
     * Open (creating if necessary) a store at {@code dbPath} using the given schema version. The
     * parent directory is created if missing.
     *
     * @param dbPath        the SQLite file, or {@code ":memory:"} for an in-memory db
     * @param schemaVersion the current schema version; a stored mismatch triggers a rebuild
     */
    public L2Store(Path dbPath, int schemaVersion) throws SQLException {
        String url = toJdbcUrl(dbPath);
        this.conn = DriverManager.getConnection(url);
        try {
            applyPragmas();
            migrate(schemaVersion);
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

    /**
     * A row read from {@code cache_entries}. The gateway applies the read-time predicates
     * (TTL expiry, per-row stale {@code patch_id}, stale {@code schema_version}).
     */
    public record Entry(String path, String body, String classification, long storedAt,
                        Long expiresAt, int schemaVersion, String patchId) {
    }

    /** Read the row for {@code path}, or {@code null} if absent. Caller applies validity predicates. */
    public synchronized Entry get(String path) throws SQLException {
        String sql = "SELECT path, body, classification, stored_at, expires_at, schema_version, patch_id "
                + "FROM cache_entries WHERE path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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

    /** Store (or overwrite) a row with {@code INSERT OR REPLACE} on the {@code path} primary key. */
    public synchronized void put(String path, String body, Classification classification, long storedAt,
                                 Long expiresAt, int schemaVersion, String patchId) throws SQLException {
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
    }

    /** Current number of rows in {@code cache_entries}. */
    public synchronized long rowCount() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM cache_entries")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** Approximate total body bytes across all rows (UTF-8 length). */
    public synchronized long totalBodyBytes() throws SQLException {
        // length() counts UTF-16 chars in SQLite; for cap purposes an approximation is fine
        // (spec §6.4 calls the byte cap "approximate"). It bounds growth without exactness.
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(LENGTH(body)), 0) FROM cache_entries")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
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
        String sql = "DELETE FROM cache_entries WHERE path IN "
                + "(SELECT path FROM cache_entries ORDER BY stored_at ASC LIMIT ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, n);
            return ps.executeUpdate();
        }
    }

    /**
     * Patch-bust (spec §5.2): delete every PERMANENT row whose {@code patch_id IS NOT NULL} (the
     * patch-scoped static rows). Match rows have {@code patch_id = NULL} and survive.
     *
     * @return the number of rows deleted
     */
    public synchronized int patchBust() throws SQLException {
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate("DELETE FROM cache_entries "
                    + "WHERE classification = 'PERMANENT' AND patch_id IS NOT NULL");
        }
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
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException ignored) {
            // best-effort close
        }
    }
}
