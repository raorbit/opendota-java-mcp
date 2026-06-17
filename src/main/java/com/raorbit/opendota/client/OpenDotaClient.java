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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

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
public class OpenDotaClient implements AutoCloseable {

    private static final String DEFAULT_BASE = "https://api.opendota.com/api";
    private static final String USER_AGENT = "opendota-mcp/1.0.0";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** Default upper bound on time a request waits for a rate-limit permit. */
    private static final Duration DEFAULT_RATE_LIMIT_BUDGET = Duration.ofSeconds(10);
    /** Default maximum number of cached responses retained before eviction. */
    private static final int DEFAULT_CACHE_MAX_ENTRIES = 4096;

    private final String apiKey;
    private final boolean keyed;
    private final String base;
    private final HttpClient httpClient;
    private final RateLimiter rateLimiter;
    private final TtlCache cache;
    /**
     * Upper bound on time a request may spend waiting for a rate-limit permit.
     * Kept comfortably under typical MCP client request timeouts so an exhausted
     * bucket fails fast with an error envelope instead of parking indefinitely.
     */
    private final Duration rateLimitBudget;
    /** In-flight cacheable fetches, so concurrent identical requests share one call. */
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    public OpenDotaClient(String apiKey) {
        this(apiKey, DEFAULT_CACHE_MAX_ENTRIES, DEFAULT_RATE_LIMIT_BUDGET);
    }

    /**
     * @param cacheMaxEntries maximum cached responses retained before eviction
     * @param rateLimitBudget maximum wait for a rate-limit permit before failing fast
     */
    public OpenDotaClient(String apiKey, int cacheMaxEntries, Duration rateLimitBudget) {
        this(apiKey, DEFAULT_BASE, cacheMaxEntries, rateLimitBudget);
    }

    /**
     * Package-private constructor allowing the API base URL to be overridden
     * (e.g. pointed at a local server in tests). The public constructors delegate
     * here with the real OpenDota base.
     */
    OpenDotaClient(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, DEFAULT_CACHE_MAX_ENTRIES, DEFAULT_RATE_LIMIT_BUDGET);
    }

    OpenDotaClient(String apiKey, String baseUrl, int cacheMaxEntries, Duration rateLimitBudget) {
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
        this.cache = new TtlCache(cacheMaxEntries);
        this.rateLimitBudget = rateLimitBudget == null ? DEFAULT_RATE_LIMIT_BUDGET : rateLimitBudget;
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
        if (!cacheable) {
            return fetch(path, cacheKey, ttl, false);
        }
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        return fetchSingleFlight(path, cacheKey, ttl);
    }

    /**
     * Fetch a cacheable path with single-flight de-duplication: when several
     * threads miss the same key at once, the first (the leader) performs the one
     * upstream call and the rest await its result, so duplicate requests do not
     * each consume a rate-limit permit.
     */
    private String fetchSingleFlight(String path, String cacheKey, Duration ttl) throws OpenDotaException {
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> leader = inFlight.putIfAbsent(cacheKey, mine);
        if (leader != null) {
            return await(leader, path);
        }
        try {
            // A leader that just finished may have populated the cache between our
            // miss and registering here, so re-check before going upstream.
            String cached = cache.get(cacheKey);
            String body = cached != null ? cached : fetch(path, cacheKey, ttl, true);
            mine.complete(body);
            return body;
        } catch (OpenDotaException | RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(cacheKey, mine);
        }
    }

    /** Await a leader's in-flight result, translating its failure back into an {@link OpenDotaException}. */
    private String await(CompletableFuture<String> leader, String path) throws OpenDotaException {
        try {
            return leader.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OpenDotaException(0, path, "request interrupted", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof OpenDotaException ode) {
                throw ode;
            }
            throw new OpenDotaException(0, path,
                    "request failed: " + (cause == null ? "unknown" : cause.getClass().getSimpleName()), cause);
        }
    }

    /** Perform the rate-limited HTTP GET, caching a 2xx body when {@code cacheable}. */
    private String fetch(String path, String cacheKey, Duration ttl, boolean cacheable) throws OpenDotaException {
        boolean acquired;
        try {
            acquired = rateLimiter.tryAcquire(rateLimitBudget);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OpenDotaException(0, path, "request interrupted", ie);
        }
        if (!acquired) {
            throw new OpenDotaException(429, path,
                    "client-side rate limit: no permit within " + rateLimitBudget.toSeconds() + "s");
        }

        String url = base + path;
        if (keyed) {
            // Encode the key so an operator-supplied value containing characters
            // illegal in a URL query cannot make URI.create throw out of getJson.
            url += (path.indexOf('?') >= 0 ? "&" : "?") + "api_key=" + encode(apiKey);
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

    /** Release the underlying {@link HttpClient}'s transport resources on shutdown. */
    @Override
    public void close() {
        httpClient.close();
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
    Duration ttlFor(String path) {
        if (path == null) {
            return Duration.ofSeconds(30);
        }
        int q = path.indexOf('?');
        String p = q >= 0 ? path.substring(0, q) : path;

        // Most-specific prefixes first.
        if (p.startsWith("/heroStats")) {
            // A 7-day aggregate over recent matches; far less volatile than live
            // data, so it shares the longer static-ish horizon rather than 60s.
            return Duration.ofHours(1);
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
