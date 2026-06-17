package com.raorbit.opendota.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin HTTP client for the OpenDota API.
 *
 * <p>Backed by a single shared {@link HttpClient}, a token-bucket
 * {@link RateLimiter} sized to the OpenDota free/keyed tiers, and a
 * {@link TtlCache} of recent responses. The public method signatures are frozen
 * (the WP1 surface); only the bodies are real here.
 *
 * <p>The API key (when supplied) is treated as a secret: it is appended to the
 * outgoing URL only, and never logged, never included in exception messages,
 * and never written to stdout.
 */
public class OpenDotaClient {

    private static final String DEFAULT_BASE = "https://api.opendota.com/api";
    private static final String USER_AGENT = "opendota-mcp/1.0.0";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final String apiKey;
    private final boolean keyed;
    private final String base;
    private final HttpClient httpClient;
    private final RateLimiter rateLimiter;
    private final TtlCache cache;

    public OpenDotaClient(String apiKey) {
        this(apiKey, DEFAULT_BASE);
    }

    /**
     * Package-private constructor allowing the API base URL to be overridden
     * (e.g. pointed at a local WireMock server in tests). The public
     * {@link #OpenDotaClient(String)} delegates here with the real OpenDota base.
     */
    OpenDotaClient(String apiKey, String baseUrl) {
        String trimmed = apiKey == null ? null : apiKey.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        this.apiKey = trimmed;
        this.keyed = this.apiKey != null;
        this.base = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.rateLimiter = new RateLimiter(keyed ? 300 : 60);
        this.cache = new TtlCache();
    }

    /**
     * Fetch the raw JSON body for the given API path.
     *
     * @param path the API path beginning with {@code '/'} (any query string is
     *             already appended by the caller, except {@code api_key})
     * @return the raw JSON response body
     * @throws OpenDotaException on any HTTP error or transport failure
     */
    public String getJson(String path) throws OpenDotaException {
        Duration ttl = ttlFor(path);
        // Cache key is the path BEFORE any api_key is appended.
        String cacheKey = path;
        boolean cacheable = ttl.compareTo(Duration.ZERO) > 0;
        if (cacheable) {
            String cached = cache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        try {
            rateLimiter.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OpenDotaException(0, path, null, ie);
        }

        String url = base + path;
        if (keyed) {
            url += (path.indexOf('?') >= 0 ? "&" : "?") + "api_key=" + apiKey;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body();
            if (status >= 200 && status <= 299) {
                if (cacheable && body != null) {
                    cache.put(cacheKey, body, ttl);
                }
                return body;
            }
            throw new OpenDotaException(status, path, body);
        } catch (IOException e) {
            // Do NOT include the URL/api_key in the message.
            throw new OpenDotaException(0, path, "request failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenDotaException(0, path, "request interrupted", e);
        }
    }

    /** @return {@code true} if a non-blank API key was supplied. */
    public boolean isKeyed() {
        return keyed;
    }

    /** URL-encode a query/path value using UTF-8. */
    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Resolve the cache TTL for a request path. The query string (if any) is
     * stripped before prefix matching, and more-specific prefixes are checked
     * before more-general ones.
     */
    private Duration ttlFor(String path) {
        if (path == null) {
            return Duration.ofSeconds(30);
        }
        int q = path.indexOf('?');
        String p = q >= 0 ? path.substring(0, q) : path;

        // Most-specific prefixes first.
        if (p.startsWith("/heroStats")) {
            return Duration.ofSeconds(60);
        }
        if (p.startsWith("/heroes")) {
            return Duration.ofHours(6);
        }
        if (p.startsWith("/constants/")) {
            return Duration.ofHours(6);
        }
        if (p.startsWith("/proMatches") || p.startsWith("/publicMatches")) {
            return Duration.ofSeconds(45);
        }
        if (p.startsWith("/live")) {
            return Duration.ZERO;
        }
        if (p.startsWith("/players/")) {
            return Duration.ofSeconds(30);
        }
        if (p.startsWith("/matches/")) {
            return Duration.ofSeconds(60);
        }
        if (p.startsWith("/search")) {
            return Duration.ofSeconds(15);
        }
        if (p.startsWith("/rankings")) {
            return Duration.ofSeconds(15);
        }
        return Duration.ofSeconds(30);
    }
}
