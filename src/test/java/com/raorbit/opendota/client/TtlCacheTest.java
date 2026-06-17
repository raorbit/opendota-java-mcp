package com.raorbit.opendota.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TtlCacheTest {

    @Test
    void getReturnsValueWithinTtl() {
        TtlCache cache = new TtlCache();
        cache.put("/heroes", "[1,2,3]", Duration.ofSeconds(30));
        assertThat(cache.get("/heroes")).isEqualTo("[1,2,3]");
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
    void clearEmptiesCache() {
        TtlCache cache = new TtlCache();
        cache.put("/heroes", "[1]", Duration.ofSeconds(30));
        cache.put("/heroStats", "[2]", Duration.ofSeconds(30));
        cache.clear();
        assertThat(cache.get("/heroes")).isNull();
        assertThat(cache.get("/heroStats")).isNull();
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
}
