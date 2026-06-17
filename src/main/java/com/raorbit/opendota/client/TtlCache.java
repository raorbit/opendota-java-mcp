package com.raorbit.opendota.client;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple thread-safe time-to-live cache of JSON strings keyed by request path.
 *
 * <p>Expiry is tracked with {@link System#nanoTime()}; expired entries are
 * evicted lazily on read.
 */
public final class TtlCache {

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

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
     * {@code ttl} is a no-op (the value is not cached).
     */
    public void put(String key, String json, Duration ttl) {
        if (key == null || json == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        long expiresAtNanos = System.nanoTime() + ttl.toNanos();
        map.put(key, new Entry(json, expiresAtNanos));
    }

    public void clear() {
        map.clear();
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
