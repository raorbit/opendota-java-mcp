package com.raorbit.opendota.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TtlCacheTest {

    @Test
    void getReturnsValueWithinTtl() {
        TtlCache cache = new TtlCache();
        cache.put("/heroes", "[1,2,3]", Duration.ofSeconds(30));
        assertThat(cache.get("/heroes")).isEqualTo("[1,2,3]");
    }

    @Test
    void tracksHitAndMissCounts() {
        TtlCache cache = new TtlCache();
        cache.get("/absent");                                   // miss
        cache.put("/heroes", "[1,2,3]", Duration.ofSeconds(30));
        cache.get("/heroes");                                   // hit
        cache.get("/heroes");                                   // hit
        cache.get("/still-absent");                             // miss

        assertThat(cache.hits()).isEqualTo(2);
        assertThat(cache.misses()).isEqualTo(2);
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.approximateBytes()).isGreaterThan(0L);
    }

    @Test
    void getReturnsNullAfterTtlElapses() throws InterruptedException {
        TtlCache cache = new TtlCache();
        cache.put("/heroes", "[1,2,3]", Duration.ofMillis(40));
        // Present immediately after a put well within the TTL window.
        assertThat(cache.get("/heroes")).isEqualTo("[1,2,3]");
        // Poll until the entry expires. A generous 2s budget with a small TTL
        // keeps this deterministic without depending on exact sleep precision.
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (cache.get("/heroes") != null && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        assertThat(cache.get("/heroes")).isNull();
    }

    @Test
    void putWithZeroTtlIsNoOp() {
        TtlCache cache = new TtlCache();
        cache.put("/live", "[]", Duration.ZERO);
        assertThat(cache.get("/live")).isNull();
    }

    @Test
    void putWithNegativeTtlIsNoOp() {
        TtlCache cache = new TtlCache();
        cache.put("/live", "[]", Duration.ofSeconds(-5));
        assertThat(cache.get("/live")).isNull();
    }

    @Test
    void evictsEntryNearestToExpiryWhenOverCapacity() {
        TtlCache cache = new TtlCache(2);
        cache.put("a", "1", Duration.ofSeconds(30));   // expires soonest
        cache.put("b", "2", Duration.ofSeconds(120));
        // At capacity (2); a new key evicts the live entry nearest to expiry ("a").
        cache.put("c", "3", Duration.ofSeconds(120));
        assertThat(cache.get("a")).isNull();
        assertThat(cache.get("b")).isEqualTo("2");
        assertThat(cache.get("c")).isEqualTo("3");
    }

    @Test
    void evictionReclaimsExpiredEntriesBeforeLiveOnes() throws InterruptedException {
        TtlCache cache = new TtlCache(2);
        cache.put("stale", "x", Duration.ofMillis(30));
        cache.put("fresh", "y", Duration.ofSeconds(120));
        // Let "stale" expire, then fill to capacity again.
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (cache.get("stale") != null && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        // The expired "stale" is reclaimed, so the live "fresh" survives.
        cache.put("new", "z", Duration.ofSeconds(120));
        assertThat(cache.get("fresh")).isEqualTo("y");
        assertThat(cache.get("new")).isEqualTo("z");
    }

    @Test
    void replacingAnExistingKeyAtCapacityDoesNotEvict() {
        TtlCache cache = new TtlCache(2);
        cache.put("a", "1", Duration.ofSeconds(120));
        cache.put("b", "2", Duration.ofSeconds(120));
        // Re-putting an existing key is not growth, so nothing is evicted.
        cache.put("a", "1-updated", Duration.ofSeconds(120));
        assertThat(cache.get("a")).isEqualTo("1-updated");
        assertThat(cache.get("b")).isEqualTo("2");
    }

    @Test
    void evictsByByteBudgetEvenWhenUnderEntryCount() {
        // High entry cap, low byte budget (20): the byte bound drives eviction.
        TtlCache cache = new TtlCache(10_000, 20);
        cache.put("a", "0123456789", Duration.ofSeconds(30));    // 10 bytes, expires soonest
        cache.put("b", "0123456789", Duration.ofSeconds(120));   // 10 bytes -> total 20, at budget
        // Adding a third 10-byte value pushes total to 30 > 20, so the
        // nearest-to-expiry live entry ("a") is evicted back under budget.
        cache.put("c", "0123456789", Duration.ofSeconds(120));
        assertThat(cache.get("a")).isNull();
        assertThat(cache.get("b")).isEqualTo("0123456789");
        assertThat(cache.get("c")).isEqualTo("0123456789");
    }

    @Test
    void valueLargerThanByteBudgetIsNotCached() {
        TtlCache cache = new TtlCache(10_000, 5);
        // A single value bigger than the whole budget cannot be cached at all.
        cache.put("big", "0123456789", Duration.ofSeconds(120));
        assertThat(cache.get("big")).isNull();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void concurrentPutAndGetIsThreadSafe() throws Exception {
        TtlCache cache = new TtlCache(10_000);
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    String key = "/p/" + tid + "/" + i;
                    cache.put(key, "v" + i, Duration.ofSeconds(60));
                    cache.get(key);
                }
                return null;
            }));
        }
        start.countDown();
        // get() on each future rethrows any exception the task hit (e.g. a
        // ConcurrentModificationException from a non-thread-safe map).
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(cache.get("/p/0/0")).isEqualTo("v0");
    }
}
