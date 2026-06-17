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
}
