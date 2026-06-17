package com.raorbit.opendota.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the OpenDota client, bound from the {@code opendota.*} prefix.
 */
@ConfigurationProperties(prefix = "opendota")
public class OpenDotaProperties {

    /** Maximum number of cached responses retained before eviction kicks in. */
    private int cacheMaxEntries = 4096;

    /**
     * Approximate upper bound on the total bytes of cached response bodies. Caps
     * heap when many large bodies are cached even while under the entry count.
     */
    private long cacheMaxBytes = 64L * 1024 * 1024;

    /** Maximum time a request waits for a rate-limit permit before failing fast. */
    private Duration rateLimitBudget = Duration.ofSeconds(10);

    /**
     * Maximum size, in bytes, of a single upstream response body. A response that
     * exceeds this is aborted rather than buffered, so a hostile or misbehaving
     * upstream cannot exhaust the heap.
     */
    private long maxResponseBytes = 16L * 1024 * 1024;

    public int getCacheMaxEntries() {
        return cacheMaxEntries;
    }

    public void setCacheMaxEntries(int cacheMaxEntries) {
        this.cacheMaxEntries = cacheMaxEntries;
    }

    public long getCacheMaxBytes() {
        return cacheMaxBytes;
    }

    public void setCacheMaxBytes(long cacheMaxBytes) {
        this.cacheMaxBytes = cacheMaxBytes;
    }

    public Duration getRateLimitBudget() {
        return rateLimitBudget;
    }

    public void setRateLimitBudget(Duration rateLimitBudget) {
        this.rateLimitBudget = rateLimitBudget;
    }

    public long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }
}
