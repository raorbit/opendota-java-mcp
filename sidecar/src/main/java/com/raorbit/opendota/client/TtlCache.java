package com.raorbit.opendota.client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple thread-safe time-to-live cache of JSON strings keyed by request path.
 *
 * <p>Expiry is tracked with {@link System#nanoTime()}; expired entries are
 * evicted lazily on read. The cache is also <em>bounded</em> on two axes: a
 * maximum entry count and an approximate maximum total byte size of the cached
 * values. Once either bound would be exceeded, a {@link #put} first reclaims any
 * already-expired entries and, if still over a bound, drops live entries nearest
 * to expiry before storing the new value. Bounding by bytes (not just count)
 * matters because a single OpenDota body can be many KB-to-MB, so a count-only
 * cap could still pin gigabytes of heap. This caps heap on a long-lived server
 * whose key space (account ids, match ids, search terms, pagination cursors) is
 * effectively unbounded and whose entries are rarely read a second time to
 * trigger lazy eviction.
 */
public final class TtlCache {

    /** Default upper bound on retained entries. */
    private static final int DEFAULT_MAX_ENTRIES = 4096;
    /** Default approximate upper bound on total bytes of cached values. */
    private static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

    private final int maxEntries;
    private final long maxBytes;
    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    /** Approximate running total of cached value sizes; a soft bound under concurrency. */
    private final AtomicLong currentBytes = new AtomicLong();
    /** Lifetime read hits (live value returned) and misses (absent or expired), for observability. */
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public TtlCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);
    }

    /** Package-private constructor allowing the entry-count bound to be overridden in tests. */
    TtlCache(int maxEntries) {
        this(maxEntries, DEFAULT_MAX_BYTES);
    }

    /** Package-private constructor allowing both bounds to be overridden in tests. */
    TtlCache(int maxEntries, long maxBytes) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
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
            misses.incrementAndGet();
            return null;
        }
        if (System.nanoTime() - entry.expiresAtNanos >= 0L) {
            // Expired: evict (only if still the same entry) and report a miss.
            removeEntry(key, entry);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.value;
    }

    /** Lifetime count of read hits (a live value was returned). */
    public long hits() {
        return hits.get();
    }

    /** Lifetime count of read misses (key absent or expired on read). */
    public long misses() {
        return misses.get();
    }

    /** Current number of retained entries (including any not-yet-reclaimed expired ones). */
    public int size() {
        return map.size();
    }

    /** Approximate total bytes of cached values (a soft, eventually-consistent tally). */
    public long approximateBytes() {
        return currentBytes.get();
    }

    /**
     * Cache {@code json} under {@code key} for {@code ttl}. A non-positive
     * {@code ttl} is a no-op (the value is not cached). A value larger than the
     * whole byte budget is never cached (per-entry ceiling). When storing would
     * exceed the entry-count or byte bound, expired entries are reclaimed first
     * and, if still over a bound, entries nearest to expiry are evicted.
     */
    public void put(String key, String json, Duration ttl) {
        if (key == null || json == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        // Measure real UTF-8 bytes (not UTF-16 char count) so this budget uses the
        // same unit as the documented byte bound and the response-size cap.
        int size = json.getBytes(StandardCharsets.UTF_8).length;
        // Per-entry ceiling: a single body too large to ever fit the budget is
        // not cached, so it cannot dominate or thrash the cache.
        if (size > maxBytes) {
            return;
        }
        long expiresAtNanos = System.nanoTime() + ttl.toNanos();
        Entry previous = map.put(key, new Entry(json, size, expiresAtNanos));
        long delta = size - (previous == null ? 0L : previous.size);
        long total = currentBytes.addAndGet(delta);
        if (map.size() > maxEntries || total > maxBytes) {
            evict();
        }
    }

    /**
     * Bound the cache on both axes. First drops every entry whose TTL has elapsed;
     * then, while still over the entry-count or byte bound, evicts live entries
     * nearest to expiry. Eviction is best-effort under concurrency (a bound may be
     * transiently exceeded), which is acceptable for a soft memory bound.
     */
    private void evict() {
        long now = System.nanoTime();
        for (Map.Entry<String, Entry> e : map.entrySet()) {
            if (now - e.getValue().expiresAtNanos >= 0L) {
                removeEntry(e.getKey(), e.getValue());
            }
        }
        while (map.size() > maxEntries || currentBytes.get() > maxBytes) {
            String nearestKey = null;
            Entry nearestEntry = null;
            long nearestExpiry = 0L;
            for (Map.Entry<String, Entry> e : map.entrySet()) {
                long exp = e.getValue().expiresAtNanos;
                // Overflow-safe "expires before", consistent with get()'s comparison.
                if (nearestKey == null || exp - nearestExpiry < 0L) {
                    nearestExpiry = exp;
                    nearestKey = e.getKey();
                    nearestEntry = e.getValue();
                }
            }
            if (nearestKey == null) {
                break;
            }
            removeEntry(nearestKey, nearestEntry);
        }
    }

    /** Remove an entry only if it is still the one mapped, keeping the byte tally in step. */
    private void removeEntry(String key, Entry entry) {
        if (map.remove(key, entry)) {
            currentBytes.addAndGet(-entry.size);
        }
    }

    private static final class Entry {
        final String value;
        final int size;
        final long expiresAtNanos;

        Entry(String value, int size, long expiresAtNanos) {
            this.value = value;
            this.size = size;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}
