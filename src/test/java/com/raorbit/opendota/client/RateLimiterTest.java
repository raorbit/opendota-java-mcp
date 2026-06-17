package com.raorbit.opendota.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RateLimiterTest {

    @Test
    void firstTryAcquireSucceeds() {
        RateLimiter limiter = new RateLimiter(60);
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void tryAcquireFailsOnceBucketDrained() {
        RateLimiter limiter = new RateLimiter(60);
        // Drain the bucket. The bucket starts full (capacity == 60). Draining
        // more than the capacity tolerates the tiny refill that accrues during
        // the loop so the bucket is genuinely empty when we assert.
        int drained = 0;
        for (int i = 0; i < 60 && limiter.tryAcquire(); i++) {
            drained++;
        }
        assertThat(drained).isGreaterThan(0);
        // Immediately after draining, far less than one token can have refilled,
        // so a further non-blocking attempt must fail.
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void acquireReturnsWithinGenerousBudget() {
        // 60 permits/minute == one token per second. With a full bucket the
        // first acquire() is immediate; even after a single drain the wait for
        // one token is ~1s, comfortably under the 2s timeout.
        RateLimiter limiter = new RateLimiter(60);
        assertThatCode(limiter::acquire).doesNotThrowAnyException();
    }
}
