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

    /**
     * Outbound rate-limit permits per minute, or {@code 0} to derive from the API
     * tier (300 keyed / 60 keyless). When several MCP server processes share one
     * API key (and so one OpenDota per-key budget), set this to
     * {@code tier_budget / expected_concurrent_processes} so their combined
     * outbound rate stays within OpenDota's real per-key ceiling. Made obsolete
     * once the requests are funnelled through a single shared sidecar.
     */
    private int rateLimitPermitsPerMinute = 0;

    /**
     * When {@code true}, this server forwards every OpenDota call to a shared local
     * sidecar (which holds the API key and owns the one rate limiter and cache)
     * instead of calling OpenDota directly. Use this when several agents share one
     * API key; see {@code docs/mcp-registration.md}. Default {@code false} = direct.
     */
    private boolean sidecarEnabled = false;

    /** Loopback host of the shared sidecar (used only when {@link #sidecarEnabled}). */
    private String sidecarHost = "127.0.0.1";

    /** Port of the shared sidecar (used only when {@link #sidecarEnabled}). */
    private int sidecarPort = 31337;

    /**
     * Optional shared secret sent to the sidecar (header {@code X-Sidecar-Token}) when the
     * sidecar is started with a matching token. Leave blank/unset (the default) when the
     * sidecar runs without auth. Used only when {@link #sidecarEnabled}.
     */
    private String sidecarToken;

    /**
     * HTTP (remote-MCP) transport settings, bound from {@code opendota.http.*}. Used only
     * by the opt-in {@code http} Spring profile (the {@code -Phttp} build); inert otherwise.
     */
    private final Http http = new Http();

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

    public int getRateLimitPermitsPerMinute() {
        return rateLimitPermitsPerMinute;
    }

    public void setRateLimitPermitsPerMinute(int rateLimitPermitsPerMinute) {
        this.rateLimitPermitsPerMinute = rateLimitPermitsPerMinute;
    }

    public boolean isSidecarEnabled() {
        return sidecarEnabled;
    }

    public void setSidecarEnabled(boolean sidecarEnabled) {
        this.sidecarEnabled = sidecarEnabled;
    }

    public String getSidecarHost() {
        return sidecarHost;
    }

    public void setSidecarHost(String sidecarHost) {
        this.sidecarHost = sidecarHost;
    }

    public int getSidecarPort() {
        return sidecarPort;
    }

    public void setSidecarPort(int sidecarPort) {
        this.sidecarPort = sidecarPort;
    }

    public String getSidecarToken() {
        return sidecarToken;
    }

    public void setSidecarToken(String sidecarToken) {
        this.sidecarToken = sidecarToken;
    }

    public Http getHttp() {
        return http;
    }

    /**
     * Settings for the opt-in HTTP (remote-MCP) transport, bound from {@code opendota.http.*}
     * (relaxed-binding env {@code OPENDOTA_HTTP_*}). Only meaningful under the {@code http}
     * Spring profile; the default stdio build never reads these.
     */
    public static class Http {

        /** How the HTTP MCP endpoint is authenticated: {@code none} or {@code bearer}. */
        public enum AuthMode {
            /** No auth filter. Only permitted on a loopback bind (the startup guard refuses otherwise). */
            NONE,
            /** Require {@code Authorization: Bearer <token>} matching {@link #bearerToken}. */
            BEARER
        }

        /**
         * Auth mode for the HTTP MCP endpoint. Defaults to {@link AuthMode#BEARER} (fail-safe):
         * a bearer token must be presented. Set to {@code none} only behind a trusted fronting
         * proxy on a loopback bind; the fail-closed startup guard refuses {@code none} (or a
         * missing token) on a non-loopback bind regardless.
         */
        private AuthMode authMode = AuthMode.BEARER;

        /**
         * Expected bearer secret, compared constant-time. Supply via env
         * {@code OPENDOTA_HTTP_BEARER_TOKEN}; treat as a secret and never commit it
         * (same handling as {@code OPENDOTA_API_KEY} / {@code OPENDOTA_SIDECAR_TOKEN}).
         */
        private String bearerToken;

        public AuthMode getAuthMode() {
            return authMode;
        }

        public void setAuthMode(AuthMode authMode) {
            this.authMode = authMode;
        }

        public String getBearerToken() {
            return bearerToken;
        }

        public void setBearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
        }
    }
}
