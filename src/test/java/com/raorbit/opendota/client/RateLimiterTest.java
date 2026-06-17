package com.raorbit.opendota.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RateLimiterTest {

    /** Drain the bucket empty, tolerating any tiny refill that accrues during the loop. */
    private static void drain(RateLimiter limiter) {
        for (int i = 0; i < 100_000 && limiter.tryAcquire(); i++) {
            // discard the permit
        }
    }

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

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    void acquireBlocksUntilATokenRefills() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(60); // 1 token/sec
        drain(limiter);
        long startNanos = System.nanoTime();
        limiter.acquire(); // empty bucket: must park until the next token accrues
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        // It genuinely blocked rather than returning immediately (full-bucket ~0ms).
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(500L);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    void tryAcquireWithBudgetBlocksThenSucceedsAfterRefill() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(60); // 1 token/sec
        drain(limiter);
        // A token refills in ~1s, within the 2s budget.
        assertThat(limiter.tryAcquire(Duration.ofSeconds(2))).isTrue();
    }

    @Test
    void tryAcquireWithShortBudgetGivesUpWhenDrained() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(60); // 1 token/sec
        drain(limiter);
        // Far less than one token refills in 50ms, so the bounded wait gives up.
        assertThat(limiter.tryAcquire(Duration.ofMillis(50))).isFalse();
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    void acquireThrowsWhenInterruptedWhileWaiting() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(60);
        drain(limiter);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                limiter.acquire();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        waiter.start();
        Thread.sleep(100); // let it park inside acquire()
        waiter.interrupt();
        waiter.join(2000);
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrentTryAcquireNeverIssuesMoreThanCapacity() throws InterruptedException {
        int capacity = 50; // ~0.83 tokens/sec refill: negligible over the test window
        RateLimiter limiter = new RateLimiter(capacity);
        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire()) {
                        granted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(4, TimeUnit.SECONDS);
        pool.shutdownNow();
        // Exactly the initial capacity is issued; a dropped lock would over-issue.
        // Allow +1 for the rare case a single token refills mid-run.
        assertThat(granted.get()).isBetween(capacity, capacity + 1);
    }
}
