package com.raorbit.opendota.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple thread-safe time-to-live cache of JSON strings keyed by request path.
 *
 * <p>Expiry is tracked with {@link System#nanoTime()}; expired entries are
 * evicted lazily on read. The cache is also <em>bounded</em>: once it holds
 * {@code maxEntries}, a {@link #put} first reclaims any already-expired entries
 * and, if still full, drops the live entry nearest to expiry before storing the
 * new value. This caps heap on a long-lived server whose key space (account
 * ids, match ids, search terms, pagination cursors) is effectively unbounded
 * and whose entries are rarely read a second time to trigger lazy eviction.
 */
public final class TtlCache {

    /** Default upper bound on retained entries. */
    private static final int DEFAULT_MAX_ENTRIES = 4096;

    private final int maxEntries;
    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

    public TtlCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /** Package-private constructor allowing the bound to be overridden in tests. */
    TtlCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        this.maxEntries = maxEntries;
    }

    /**
     * @return the cached value, or {@code null} on miss or expiry
     */
    public String get(String key) {
        if (key == null) {
            return null;
        }
        Entry entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (System.nanoTime() - entry.expiresAtNanos >= 0L) {
            // Expired: evict (only if still the same entry) and report a miss.
            map.remove(key, entry);
            return null;
        }
        return entry.value;
    }

    /**
     * Cache {@code json} under {@code key} for {@code ttl}. A non-positive
     * {@code ttl} is a no-op (the value is not cached). When the cache is at
     * capacity and this is a new key, expired entries are reclaimed first and,
     * if still full, the entry nearest to expiry is evicted before storing.
     */
    public void put(String key, String json, Duration ttl) {
        if (key == null || json == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        if (map.size() >= maxEntries && !map.containsKey(key)) {
            evict();
        }
        long expiresAtNanos = System.nanoTime() + ttl.toNanos();
        map.put(key, new Entry(json, expiresAtNanos));
    }

    /**
     * Bound the cache. First drops every entry whose TTL has elapsed; if the map
     * is still at capacity, evicts the single live entry nearest to expiry.
     * Eviction is best-effort under concurrency (the cap may be transiently
     * exceeded), which is acceptable for a soft memory bound.
     */
    private void evict() {
        long now = System.nanoTime();
        map.values().removeIf(e -> now - e.expiresAtNanos >= 0L);
        if (map.size() < maxEntries) {
            return;
        }
        String nearestKey = null;
        long nearestExpiry = 0L;
        for (Map.Entry<String, Entry> e : map.entrySet()) {
            long exp = e.getValue().expiresAtNanos;
            // Overflow-safe "expires before", consistent with get()'s comparison.
            if (nearestKey == null || exp - nearestExpiry < 0L) {
                nearestExpiry = exp;
                nearestKey = e.getKey();
            }
        }
        if (nearestKey != null) {
            map.remove(nearestKey);
        }
    }

    private static final class Entry {
        final String value;
        final long expiresAtNanos;

        Entry(String value, long expiresAtNanos) {
            this.value = value;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}
