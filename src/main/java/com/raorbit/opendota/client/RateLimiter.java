package com.raorbit.opendota.client;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * Thread-safe token-bucket rate limiter using {@link System#nanoTime()}.
 *
 * <p>Refills at a steady rate of {@code permitsPerMinute / 60s}, capped at
 * {@code permitsPerMinute} tokens. Framework-agnostic plain Java.
 *
 * <p>Waiters are not ordered: fairness is best-effort, so under sustained
 * saturation a late arrival may acquire a refilled permit ahead of a
 * longer-waiting thread.
 */
public final class RateLimiter {

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private final double capacity;
    private final double refillPerNano;

    /** Guards {@link #tokens} and {@link #lastRefillNanos}. */
    private final Object lock = new Object();

    private double tokens;
    private long lastRefillNanos;

    public RateLimiter(int permitsPerMinute) {
        if (permitsPerMinute <= 0) {
            throw new IllegalArgumentException("permitsPerMinute must be positive: " + permitsPerMinute);
        }
        this.capacity = permitsPerMinute;
        this.refillPerNano = (double) permitsPerMinute / NANOS_PER_MINUTE;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Blocks (parking the current thread) until a permit is available, then
     * consumes one.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            long waitNanos;
            synchronized (lock) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                // Nanos until the bucket accrues one full token.
                double deficit = 1.0 - tokens;
                waitNanos = (long) Math.ceil(deficit / refillPerNano);
                if (waitNanos < 1L) {
                    waitNanos = 1L;
                }
            }
            LockSupport.parkNanos(waitNanos);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    /**
     * Blocks until a permit is available or {@code maxWait} elapses, whichever
     * comes first, then consumes a permit if one was obtained. A non-positive
     * {@code maxWait} degrades to a single non-blocking {@link #tryAcquire()}.
     *
     * @param maxWait the maximum time to wait for a permit
     * @return {@code true} if a permit was consumed, {@code false} if the budget
     *         elapsed first
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean tryAcquire(Duration maxWait) throws InterruptedException {
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            return tryAcquire();
        }
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            long waitNanos;
            synchronized (lock) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return true;
                }
                double deficit = 1.0 - tokens;
                waitNanos = (long) Math.ceil(deficit / refillPerNano);
                if (waitNanos < 1L) {
                    waitNanos = 1L;
                }
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return false;
            }
            LockSupport.parkNanos(Math.min(waitNanos, remaining));
        }
    }

    /**
     * Attempts to consume one permit without blocking.
     *
     * @return {@code true} iff a permit was consumed
     */
    public boolean tryAcquire() {
        synchronized (lock) {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** A best-effort snapshot of currently-available permits (after a refill), for observability. */
    public double availablePermits() {
        synchronized (lock) {
            refill();
            return tokens;
        }
    }

    /** The configured steady-state budget — the token-bucket capacity (permits per minute). */
    public int permitsPerMinute() {
        return (int) capacity;
    }

    /** Must be called while holding {@link #lock}. */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0L) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefillNanos = now;
        }
    }
}
