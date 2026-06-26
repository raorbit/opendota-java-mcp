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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The durable, SQLite-backed L2 store (see {@code docs/l2-cache-design.md} §6, §7).
 *
 * <p>One {@code cache_entries} row per cached response, keyed by the OpenDota path (incl. query —
 * the same key L1 uses). On open it sets WAL + busy_timeout pragmas, runs a hand-rolled
 * drop+rebuild migration keyed on {@code schema_version}, and exposes a small CRUD surface the
 * {@link L2CachingGateway} drives.
 *
 * <p>Concurrency (spec §7.1): writes ({@link #put}, eviction, {@link #patchBust}, rebuild) are
 * serialized on this store's monitor over a single write connection. Reads ({@link #get}) instead
 * borrow from a bounded pool of dedicated read-only connections, so concurrent L2-hit reads run in
 * parallel (WAL allows many readers alongside the one writer) rather than serializing behind a lock.
 * An in-memory db has no pool — a second {@code :memory:} connection would be a separate empty db —
 * so it shares the write connection and reads there serialize with writes (correct for one db).
 *
 * <p>Errors are <em>not</em> swallowed here — they surface as {@link SQLException} so the gateway
 * can decide policy (count an {@code l2Error} and fall back to passthrough; spec §7.2).
 */
public final class L2Store implements AutoCloseable {

    /** Bumped to force a destructive drop+rebuild of {@code cache_entries} (spec §6.3). */
    public static final int SCHEMA_VERSION = 1;

    /** Default number of read-only connections when not configured (spec §7.1). */
    static final int DEFAULT_READ_POOL = 4;
    /** Upper bound on the pool so an over-large config value can't exhaust file handles. */
    static final int MAX_READ_POOL = 64;
    /** Max wait to borrow a read connection before degrading to a cache miss (best-effort, spec §7.2). */
    private static final long BORROW_TIMEOUT_MILLIS = 5000L;

    private static final String META_SCHEMA_VERSION = "schema_version";
    private static final String META_PATCH_ID = "patch_id";

    /** The single write connection; all writes are serialized on this store's monitor. */
    private final Connection conn;
    /**
     * Bounded pool of dedicated read-only connections for the hot {@link #get} path, so concurrent
     * L2-hit reads run in parallel (WAL allows many readers + one writer). {@code null} for an
     * in-memory db — a second {@code :memory:} connection is a separate empty db — in which case
     * {@link #get} shares {@link #conn} under {@code this}.
     */
    private final BlockingQueue<Connection> readPool;

    // O(1) running totals so enforceCaps() avoids full-table COUNT/SUM scans on the request path
    // (spec §6.4). Seeded once on open, then maintained by put/evictOldest/patchBust under the
    // connection monitor. Bytes are real UTF-8 bytes on every axis — Java getBytes(UTF_8).length on
    // insert, SQLite LENGTH(CAST(body AS BLOB)) on seed/evict — so the total can't drift on non-ASCII.
    private final AtomicLong currentRows = new AtomicLong();
    private final AtomicLong currentBytes = new AtomicLong();

    // Pinned (watched-archive) running totals, a strict subset of the global totals above. Seeded in
    // initCounters() from a classification='PINNED' COUNT/SUM and maintained in put()/evictOldestPinned()
    // exactly like the global totals. The main caps are enforced against (current - pinned) so a growing
    // archive never forces ordinary-cache eviction; the watched caps are enforced against these (spec §6.5).
    private final AtomicLong pinnedRows = new AtomicLong();
    private final AtomicLong pinnedBytes = new AtomicLong();

    /**
     * Open (creating if necessary) a store at {@code dbPath} using the given schema version. The
     * parent directory is created if missing.
     *
     * @param dbPath        the SQLite file, or {@code ":memory:"} for an in-memory db
     * @param schemaVersion the current schema version; a stored mismatch triggers a rebuild
     */
    public L2Store(Path dbPath, int schemaVersion) throws SQLException {
        this(dbPath, schemaVersion, DEFAULT_READ_POOL);
    }

    /**
     * As {@link #L2Store(Path, int)} but with an explicit read-connection pool size (clamped to
     * {@code [1, }{@value #MAX_READ_POOL}{@code ]}). Ignored for an in-memory db, which shares the
     * one connection.
     */
    public L2Store(Path dbPath, int schemaVersion, int readPoolSize) throws SQLException {
        String url = toJdbcUrl(dbPath);
        boolean inMemory = ":memory:".equals(dbPath.toString());
        int poolSize = Math.max(1, Math.min(MAX_READ_POOL, readPoolSize));
        this.conn = DriverManager.getConnection(url);
        try {
            applyPragmas();
            migrate(schemaVersion);
            initCounters();
            // File-backed: a pool of read-only connections so L2-hit reads run in parallel under WAL.
            // In-memory: no pool (a second :memory: connection is a separate empty db); get() shares conn.
            this.readPool = inMemory ? null : openReadPool(url, poolSize);
        } catch (SQLException e) {
            closeQuietly();
            throw e;
        }
    }

    /** Open {@code size} read-only connections; on any failure, close the ones already opened and rethrow. */
    private static BlockingQueue<Connection> openReadPool(String url, int size) throws SQLException {
        BlockingQueue<Connection> pool = new ArrayBlockingQueue<>(size);
        try {
            for (int i = 0; i < size; i++) {
                Connection c = DriverManager.getConnection(url);
                applyReadPragmas(c);
                pool.add(c);
            }
            return pool;
        } catch (SQLException e) {
            for (Connection c : pool) {
                closeOne(c);
            }
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
    private static void applyReadPragmas(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
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

    /** Seed the global and pinned running row/byte totals from the table once, after migration. */
    private void initCounters() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*), COALESCE(SUM(LENGTH(CAST(body AS BLOB))), 0) FROM cache_entries")) {
            if (rs.next()) {
                currentRows.set(rs.getLong(1));
                currentBytes.set(rs.getLong(2));
            }
        }
        // Seed the pinned sub-totals separately (a watched archive survives a restart, so they must be
        // recovered from disk, not assumed zero). Same UTF-8 byte basis as the global seed above.
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*), COALESCE(SUM(LENGTH(CAST(body AS BLOB))), 0) "
                             + "FROM cache_entries WHERE classification = 'PINNED'")) {
            if (rs.next()) {
                pinnedRows.set(rs.getLong(1));
                pinnedBytes.set(rs.getLong(2));
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
        if (readPool == null) {
            // In-memory: share the single connection, serialized with writes on this store's monitor.
            synchronized (this) {
                return queryOne(conn, path);
            }
        }
        // File-backed: borrow a dedicated read connection so concurrent reads run in parallel (WAL lets
        // each see the latest committed snapshot). Best-effort: if the pool is saturated past the borrow
        // timeout — near-impossible for microsecond point lookups — degrade to a miss so the gateway
        // fetches upstream rather than blocking the request indefinitely (spec §7.2).
        Connection c;
        try {
            c = readPool.poll(BORROW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (c == null) {
            return null;
        }
        try {
            return queryOne(c, path);
        } finally {
            readPool.add(c);   // capacity == pool size and we removed one, so this never blocks/fails
        }
    }

    /** Run the single-row lookup on the given connection; {@code null} if absent. */
    private static Entry queryOne(Connection c, String path) throws SQLException {
        String sql = "SELECT path, body, classification, stored_at, expires_at, schema_version, patch_id "
                + "FROM cache_entries WHERE path = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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

    // --- visible for testing: assert the read pool's capacity and borrow/return semantics ---

    /** Total read-pool capacity (0 for an in-memory store, which has no pool). */
    int readPoolCapacity() {
        return readPool == null ? 0 : readPool.size() + readPool.remainingCapacity();
    }

    /** Borrow a read connection (waiting up to {@code timeoutMillis}); {@code null} if the pool is empty. */
    Connection borrowReadForTest(long timeoutMillis) throws InterruptedException {
        if (readPool == null) {
            return conn;
        }
        return readPool.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** Return a connection borrowed via {@link #borrowReadForTest} to the pool. */
    void returnReadForTest(Connection c) {
        if (readPool != null && c != null && c != conn) {
            readPool.add(c);
        }
    }

    /** Store (or overwrite) a row with {@code INSERT OR REPLACE} on the {@code path} primary key. */
    public synchronized void put(String path, String body, Classification classification, long storedAt,
                                 Long expiresAt, int schemaVersion, String patchId) throws SQLException {
        // INSERT OR REPLACE may overwrite an existing row, so net out the old body's bytes (and skip
        // the row increment) to keep the running totals exact across overwrites. Also read the old
        // classification so the pinned sub-totals net out correctly when a row crosses the PINNED
        // boundary (e.g. a PERMANENT match that, on re-store, upgrades to PINNED).
        long oldBytes = 0L;
        boolean existed = false;
        boolean wasPinned = false;
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT LENGTH(CAST(body AS BLOB)), classification FROM cache_entries WHERE path = ?")) {
            sel.setString(1, path);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) {
                    existed = true;
                    oldBytes = rs.getLong(1);
                    wasPinned = Classification.PINNED.name().equals(rs.getString(2));
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
        long newBytes = utf8Len(body);
        if (!existed) {
            currentRows.incrementAndGet();
        }
        currentBytes.addAndGet(newBytes - oldBytes);
        // Maintain the pinned sub-totals by the net change across the PINNED boundary: +1 row / +newBytes
        // if the new row is pinned, -1 row / -oldBytes if the replaced row was pinned (each conditional).
        boolean isPinned = classification == Classification.PINNED;
        if (isPinned) {
            if (!wasPinned) {
                pinnedRows.incrementAndGet();
            }
            pinnedBytes.addAndGet(newBytes - (wasPinned ? oldBytes : 0L));
        } else if (wasPinned) {
            pinnedRows.decrementAndGet();
            pinnedBytes.addAndGet(-oldBytes);
        }
    }

    /** Current number of rows in {@code cache_entries} (O(1) running total, maintained on writes). */
    public long rowCount() {
        return currentRows.get();
    }

    /**
     * Total body bytes across all rows (O(1) running total). Counts real UTF-8 bytes on every axis —
     * {@code getBytes(UTF_8).length} on insert, {@code LENGTH(CAST(body AS BLOB))} on seed/evict — so the
     * total can't drift on non-ASCII bodies. (Spec §6.4 only requires an "approximate" byte cap; this is
     * exact.)
     */
    public long totalBodyBytes() {
        return currentBytes.get();
    }

    /** Current number of PINNED (watched-archive) rows (O(1) running total). */
    public long pinnedRowCount() {
        return pinnedRows.get();
    }

    /** Total body bytes across PINNED (watched-archive) rows (O(1) running total, UTF-8 bytes). */
    public long pinnedBodyBytes() {
        return pinnedBytes.get();
    }

    /**
     * Evict the {@code n} oldest <em>non-PINNED</em> rows by {@code stored_at} ascending (LRU-ish,
     * spec §6.4). PERMANENT (non-pinned) rows are <em>not</em> exempt — once over a cap the oldest go
     * regardless of class — but PINNED (watched-archive) rows are skipped entirely: they are governed
     * by the separate watched budget ({@link #evictOldestPinned}), never the main cap.
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
                + "(SELECT path FROM cache_entries WHERE classification != 'PINNED' "
                + "ORDER BY stored_at ASC LIMIT ?) "
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
     * Evict the {@code n} oldest <em>PINNED</em> rows by {@code stored_at} ascending — the watched
     * archive's own LRU eviction, run only when a positive watched cap is exceeded (spec §6.5).
     * Decrements <em>both</em> the global totals and the pinned sub-totals by the deleted rows, since
     * a pinned row counts in both.
     *
     * @return the number of rows actually deleted
     */
    public synchronized int evictOldestPinned(int n) throws SQLException {
        if (n <= 0) {
            return 0;
        }
        String sql = "DELETE FROM cache_entries WHERE path IN "
                + "(SELECT path FROM cache_entries WHERE classification = 'PINNED' "
                + "ORDER BY stored_at ASC LIMIT ?) "
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
        pinnedRows.addAndGet(-deleted);
        pinnedBytes.addAndGet(-freedBytes);
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
        return enforceCaps(maxRows, maxBytes, 0, 0, System.currentTimeMillis());
    }

    /**
     * As {@link #enforceCaps(long, long)} but with an explicit {@code now} and no watched caps
     * ({@code 0,0} = the watched archive is unbounded). Kept as a delegator so existing callers/tests
     * compile unchanged.
     *
     * @return the total number of rows evicted (expired + cap)
     */
    public synchronized int enforceCaps(long maxRows, long maxBytes, long now) throws SQLException {
        return enforceCaps(maxRows, maxBytes, 0, 0, now);
    }

    /**
     * Bring the table within both the main caps and the watched-archive caps, all under this store's
     * monitor so the check and the eviction are atomic (spec §6.4, §6.5). The two budgets are
     * independent:
     * <ul>
     *   <li><b>Main caps</b> ({@code maxRows}/{@code maxBytes}) govern <em>non-pinned</em> data only,
     *       compared against {@code current - pinned}, and {@link #evictOldest} never touches a PINNED
     *       row — so a growing archive can't force ordinary-cache eviction, and the main caps stay
     *       hard caps on ordinary data.</li>
     *   <li><b>Watched caps</b> ({@code watchedMaxRows}/{@code watchedMaxBytes}) govern <em>pinned</em>
     *       data, compared against the pinned sub-totals and enforced via {@link #evictOldestPinned};
     *       {@code 0} = unlimited (never delete pinned rows on that axis), so they are skipped.</li>
     * </ul>
     * Reclaims expired TTL rows <em>first</em>, so a dead-but-newer TTL row can't push out a
     * live-but-older PERMANENT row and dead rows don't consume the caps.
     *
     * @return the total number of rows evicted (expired + main cap + watched cap)
     */
    public synchronized int enforceCaps(long maxRows, long maxBytes, long watchedMaxRows,
                                        long watchedMaxBytes, long now) throws SQLException {
        int total = evictExpired(now);

        // --- main caps: against NON-pinned counts (current - pinned) ---
        long nonPinnedOverRows = (currentRows.get() - pinnedRows.get()) - maxRows;
        // Bounded loop (not a single shot): now that evictOldest excludes PINNED, one call could
        // under-delete if the LIMIT window is full of pinned rows, so loop until under cap or no
        // progress (deleted == 0 break).
        while (nonPinnedOverRows > 0) {
            int deleted = evictOldest((int) Math.min(Integer.MAX_VALUE, nonPinnedOverRows));
            if (deleted == 0) {
                break;
            }
            total += deleted;
            nonPinnedOverRows = (currentRows.get() - pinnedRows.get()) - maxRows;
        }
        // Byte cap: evict oldest non-pinned in small batches until the non-pinned byte total is under
        // budget (or no non-pinned rows remain).
        while ((currentBytes.get() - pinnedBytes.get()) > maxBytes
                && (currentRows.get() - pinnedRows.get()) > 0) {
            int deleted = evictOldest((int) Math.max(1L,
                    Math.min(64L, currentRows.get() - pinnedRows.get())));
            if (deleted == 0) {
                break;
            }
            total += deleted;
        }

        // --- watched caps: against PINNED counts; 0 = unlimited (skip) ---
        if (watchedMaxRows > 0) {
            long overPinnedRows = pinnedRows.get() - watchedMaxRows;
            while (overPinnedRows > 0) {
                int deleted = evictOldestPinned((int) Math.min(Integer.MAX_VALUE, overPinnedRows));
                if (deleted == 0) {
                    break;
                }
                total += deleted;
                overPinnedRows = pinnedRows.get() - watchedMaxRows;
            }
        }
        if (watchedMaxBytes > 0) {
            // Evict the archive's oldest pinned rows one at a time until the pinned byte total fits, so
            // a large body can't over-evict newer pinned rows past the budget (the watched archive is the
            // user's personal data — prefer keeping as much as fits to a coarse batch overshoot).
            while (pinnedBytes.get() > watchedMaxBytes && pinnedRows.get() > 0) {
                int deleted = evictOldestPinned(1);
                if (deleted == 0) {
                    break;
                }
                total += deleted;
            }
        }
        return total;
    }

    /**
     * Delete every TTL row whose {@code expires_at} has passed ({@code <= now}), reclaiming dead rows
     * before they count against the caps or outlive a still-valid PERMANENT row. PERMANENT <em>and
     * PINNED</em> rows have {@code expires_at = NULL} and are never matched (the watched archive is
     * permanent). A single {@code DELETE ... RETURNING} so the deleted rows and their summed bytes are
     * the same set (mirrors {@link #patchBust()}).
     *
     * <p>Liveness is request-driven (spec §6.4): this runs inside {@link #enforceCaps}, i.e. after a
     * store — a write-idle/read-heavy db still holds dead rows between stores, which the read predicate
     * filters out anyway. Not a background sweep.
     *
     * @return the number of rows deleted
     */
    public synchronized int evictExpired(long now) throws SQLException {
        String sql = "DELETE FROM cache_entries WHERE expires_at IS NOT NULL AND expires_at <= ? "
                + "RETURNING LENGTH(CAST(body AS BLOB))";
        int deleted = 0;
        long freedBytes = 0L;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
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
     * Patch-bust (spec §5.2): delete every PERMANENT row whose {@code patch_id IS NOT NULL} (the
     * patch-scoped static rows). Match rows have {@code patch_id = NULL} and survive; PINNED rows are
     * a distinct classification (never {@code 'PERMANENT'}) so they too are intentionally excluded.
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
        // Drain and close every pooled read connection (none is the write conn — in-memory has no pool),
        // then the write connection. Borrowed-but-unreturned connections only occur at JVM shutdown.
        if (readPool != null) {
            for (Connection c = readPool.poll(); c != null; c = readPool.poll()) {
                closeOne(c);
            }
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
