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

    /** Maximum time a request waits for a rate-limit permit before failing fast. */
    private Duration rateLimitBudget = Duration.ofSeconds(10);

    public int getCacheMaxEntries() {
        return cacheMaxEntries;
    }

    public void setCacheMaxEntries(int cacheMaxEntries) {
        this.cacheMaxEntries = cacheMaxEntries;
    }

    public Duration getRateLimitBudget() {
        return rateLimitBudget;
    }

    public void setRateLimitBudget(Duration rateLimitBudget) {
        this.rateLimitBudget = rateLimitBudget;
    }
}
