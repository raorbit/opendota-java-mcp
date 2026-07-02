package com.raorbit.opendota.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

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
    /**
     * Extended request timeout for OpenDota's known-slow endpoints (notably {@code /search}, whose
     * full-text query against the players table is routinely slow and times out at the default).
     */
    private static final Duration SLOW_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Request timeout a FORWARDING client applies to the loopback sidecar hop. It must cover the sidecar's
     * whole service time for a path, which is much longer than a direct call's: the sidecar may park up to
     * its own rate-limit budget (~10s) waiting for a shared permit under multi-agent contention, then spend
     * its own upstream timeout, plus — for a direct GET — up to two transient-failure retries with backoff.
     * Sizing the loopback timeout to the direct 15s/30s would spuriously fail a contended-but-healthy
     * sidecar mid-fetch (wasting the scarce permit and the response it is about to cache), so it is set
     * well above that. This covers the common contended case and normal transient retries; a pathological
     * back-to-back-5xx retry storm can still exceed it, but that is rare and self-limiting.
     */
    private static final Duration FORWARDING_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** As {@link #FORWARDING_REQUEST_TIMEOUT} but for the sidecar's slow endpoints ({@code /search}, {@code /explorer}). */
    private static final Duration FORWARDING_SLOW_REQUEST_TIMEOUT = Duration.ofSeconds(90);
    /** Max retries of an idempotent GET after a transient upstream failure (a 5xx, or a fast-endpoint timeout). */
    private static final int MAX_UPSTREAM_RETRIES = 2;
    /** Initial backoff between upstream retries (doubles up to the cap). */
    private static final Duration UPSTREAM_RETRY_BASE_BACKOFF = Duration.ofMillis(250);
    /** Maximum backoff between upstream retries. */
    private static final Duration UPSTREAM_RETRY_MAX_BACKOFF = Duration.ofSeconds(1);
    /** Cap on how long an upstream {@code Retry-After} hint may make a retry wait, so a tool call can't hang. */
    private static final Duration MAX_RETRY_AFTER_BACKOFF = Duration.ofSeconds(5);
    /** Default upper bound on time a request waits for a rate-limit permit. */
    private static final Duration DEFAULT_RATE_LIMIT_BUDGET = Duration.ofSeconds(10);
    /** Default maximum number of cached responses retained before eviction. */
    private static final int DEFAULT_CACHE_MAX_ENTRIES = 4096;
    /** Default approximate upper bound on total bytes of cached response bodies. */
    private static final long DEFAULT_CACHE_MAX_BYTES = 64L * 1024 * 1024;
    /** Default maximum size of a single upstream response body before it is aborted. */
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 16L * 1024 * 1024;
    /**
     * Hard upper bound on the configurable response cap, kept well below the JVM
     * array-size limit so an oversized cap can never drive the read buffer into an
     * {@link OutOfMemoryError} (which would bypass the clean error-envelope path).
     */
    private static final long MAX_RESPONSE_BYTES_CEILING = 256L * 1024 * 1024;
    /** Maximum length of an upstream error body surfaced in the error envelope. */
    private static final int MAX_UPSTREAM_SNIPPET = 512;
    /** Permits/min sentinel: {@code 0} = derive from the tier (300 keyed / 60 keyless). */
    private static final int DEFAULT_RATE_LIMIT_PERMITS_PER_MINUTE = 0;
    /**
     * Total time a forwarding client retries a refused sidecar connection before giving up.
     * Sized for a brief sidecar start-up bind race, <em>not</em> for a sidecar that is down:
     * a refused connection is indistinguishable from "not running / wrong port", so every
     * tool call stalls for this long when the sidecar is stopped or misconfigured. Keep it
     * short so a dead sidecar fails fast with a clean error instead of hanging the call.
     */
    private static final Duration SIDECAR_RETRY_BUDGET = Duration.ofSeconds(3);
    /** Initial backoff between sidecar connection retries (doubles up to the cap). */
    private static final Duration SIDECAR_RETRY_BASE_BACKOFF = Duration.ofMillis(200);
    /** Maximum backoff between sidecar connection retries. */
    private static final Duration SIDECAR_RETRY_MAX_BACKOFF = Duration.ofSeconds(2);
    /** Request header carrying the optional shared secret to a token-gated sidecar. */
    private static final String SIDECAR_TOKEN_HEADER = "X-Sidecar-Token";

    private final String apiKey;
    private final boolean keyed;
    private final String base;
    private final HttpClient httpClient;
    private final RateLimiter rateLimiter;
    private final TtlCache cache;
    /**
     * Upper bound, in bytes, on a single upstream response body. A response larger
     * than this is aborted mid-stream rather than buffered, so a hostile or
     * misbehaving upstream cannot exhaust the heap.
     */
    private final long maxResponseBytes;
    /**
     * Upper bound on the time a single {@code getJson} call may spend waiting for a rate-limit permit,
     * <em>summed across its retry attempts</em> (a shared per-call deadline in {@code fetch()}, not a
     * fresh budget per attempt). Kept comfortably under typical MCP client request timeouts so an
     * exhausted bucket fails fast with an error envelope instead of parking indefinitely. This bounds
     * cumulative permit-wait only — backoff sleeps and per-request timeouts are additional.
     */
    private final Duration rateLimitBudget;
    /** In-flight cacheable fetches, so concurrent identical requests share one call. */
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    /**
     * When true, this client is a thin forwarder to a local sidecar that owns the
     * shared cache, single-flight and rate limiter, so it bypasses all three and
     * retries briefly on a refused connection (the sidecar may still be starting).
     */
    private final boolean forwarding;
    /**
     * Optional shared secret sent as the {@value #SIDECAR_TOKEN_HEADER} header on every
     * forwarded request, to match a token-gated sidecar. {@code null} = send no header
     * (the default, matching a sidecar with auth disabled).
     */
    private final String forwardingToken;

    public OpenDotaClient(String apiKey) {
        this(apiKey, DEFAULT_CACHE_MAX_ENTRIES, DEFAULT_CACHE_MAX_BYTES,
                DEFAULT_RATE_LIMIT_BUDGET, DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_RATE_LIMIT_PERMITS_PER_MINUTE);
    }

    /**
     * @param cacheMaxEntries           maximum cached responses retained before eviction
     * @param cacheMaxBytes             approximate maximum total bytes of cached bodies
     * @param rateLimitBudget           maximum wait for a rate-limit permit before failing fast
     * @param maxResponseBytes          maximum size of a single upstream response body
     * @param rateLimitPermitsPerMinute outbound permits/min for the limiter, or {@code 0} to use
     *                                  the tier default (300 keyed / 60 keyless)
     */
    public OpenDotaClient(String apiKey, int cacheMaxEntries, long cacheMaxBytes,
                          Duration rateLimitBudget, long maxResponseBytes, int rateLimitPermitsPerMinute) {
        this(apiKey, DEFAULT_BASE, cacheMaxEntries, cacheMaxBytes, rateLimitBudget, maxResponseBytes,
                rateLimitPermitsPerMinute);
    }

    /**
     * Create a forwarding client that proxies every GET to a sidecar base URL
     * (e.g. {@code http://127.0.0.1:31337/api}), bypassing the local cache,
     * single-flight and rate limiter — the sidecar owns those and holds the API
     * key. Brief connection retries cover a sidecar that is still starting up.
     *
     * @param baseUrl          the sidecar base URL (its path space mirrors OpenDota's)
     * @param maxResponseBytes inbound response-size cap, guarding against a misbehaving sidecar
     */
    public static OpenDotaClient forwardingTo(String baseUrl, long maxResponseBytes) {
        return forwardingTo(baseUrl, maxResponseBytes, null);
    }

    /**
     * As {@link #forwardingTo(String, long)}, but also sends {@code token} as the
     * {@value #SIDECAR_TOKEN_HEADER} header so a token-gated sidecar accepts the request.
     * A {@code null}/blank token sends no header (matching a sidecar with auth disabled).
     *
     * @param token the shared secret the sidecar expects, or {@code null} for no auth
     */
    public static OpenDotaClient forwardingTo(String baseUrl, long maxResponseBytes, String token) {
        return new OpenDotaClient(null, baseUrl, DEFAULT_CACHE_MAX_ENTRIES, DEFAULT_CACHE_MAX_BYTES,
                DEFAULT_RATE_LIMIT_BUDGET, maxResponseBytes, DEFAULT_RATE_LIMIT_PERMITS_PER_MINUTE, true, token);
    }

    /**
     * Package-private constructor allowing the API base URL to be overridden
     * (e.g. pointed at a local server in tests). The public constructors delegate
     * here with the real OpenDota base.
     */
    OpenDotaClient(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, DEFAULT_CACHE_MAX_ENTRIES, DEFAULT_CACHE_MAX_BYTES,
                DEFAULT_RATE_LIMIT_BUDGET, DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_RATE_LIMIT_PERMITS_PER_MINUTE);
    }

    /** Package-private constructor for tests that need to override the response-size cap. */
    OpenDotaClient(String apiKey, String baseUrl, long maxResponseBytes) {
        this(apiKey, baseUrl, DEFAULT_CACHE_MAX_ENTRIES, DEFAULT_CACHE_MAX_BYTES,
                DEFAULT_RATE_LIMIT_BUDGET, maxResponseBytes, DEFAULT_RATE_LIMIT_PERMITS_PER_MINUTE);
    }

    OpenDotaClient(String apiKey, String baseUrl, int cacheMaxEntries, long cacheMaxBytes,
                   Duration rateLimitBudget, long maxResponseBytes, int rateLimitPermitsPerMinute) {
        this(apiKey, baseUrl, cacheMaxEntries, cacheMaxBytes, rateLimitBudget, maxResponseBytes,
                rateLimitPermitsPerMinute, false, null);
    }

    private OpenDotaClient(String apiKey, String baseUrl, int cacheMaxEntries, long cacheMaxBytes,
                           Duration rateLimitBudget, long maxResponseBytes, int rateLimitPermitsPerMinute,
                           boolean forwarding, String forwardingToken) {
        String trimmed = apiKey == null ? null : apiKey.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        this.apiKey = trimmed;
        this.keyed = this.apiKey != null;
        this.base = baseUrl;
        // Fail fast on invalid tunables, uniformly with TtlCache (which rejects a
        // non-positive entry/byte bound), rather than silently coercing some knobs
        // while others crash bean creation. Validate before allocating the transport
        // so a misconfigured client never leaves an unclosed HttpClient behind.
        if (rateLimitBudget == null || rateLimitBudget.isNegative()) {
            throw new IllegalArgumentException("rateLimitBudget must not be null or negative: " + rateLimitBudget);
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive: " + maxResponseBytes);
        }
        if (maxResponseBytes > MAX_RESPONSE_BYTES_CEILING) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must not exceed " + MAX_RESPONSE_BYTES_CEILING + ": " + maxResponseBytes);
        }
        if (rateLimitPermitsPerMinute < 0) {
            throw new IllegalArgumentException(
                    "rateLimitPermitsPerMinute must not be negative (0 = tier default): " + rateLimitPermitsPerMinute);
        }
        this.rateLimitBudget = rateLimitBudget;
        this.maxResponseBytes = maxResponseBytes;
        this.cache = new TtlCache(cacheMaxEntries, cacheMaxBytes);
        // 0 means "derive from the tier"; an explicit positive value lets co-running
        // processes that share one API key split the real per-key budget so their
        // combined outbound rate stays within OpenDota's ceiling.
        int permits = rateLimitPermitsPerMinute > 0 ? rateLimitPermitsPerMinute : (keyed ? 300 : 60);
        this.rateLimiter = new RateLimiter(permits);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.forwarding = forwarding;
        String trimmedToken = forwardingToken == null ? null : forwardingToken.trim();
        this.forwardingToken = (trimmedToken == null || trimmedToken.isEmpty()) ? null : trimmedToken;
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
        if (forwarding) {
            // The sidecar owns caching, single-flight and rate limiting; just forward
            // the GET (with brief connection retries) and return its response verbatim.
            return fetch(path, path, Duration.ZERO, false);
        }
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
     * Issue a POST to the given API path (no request body) and return the raw response body.
     *
     * <p>This is the one write path in an otherwise read-only client: it backs only the opt-in,
     * flag-gated write tools (parse requests / player refreshes). Unlike {@link #getJson} a POST is
     * never cached or single-flighted (it is not idempotent in effect), but it is still rate-limited
     * and has its {@code api_key} / forwarding token applied the same way, and the same key-scrubbing
     * and response-size cap protect its error path. OpenDota charges {@code POST /request} at roughly
     * 10x a normal call against the rate limit (each attempt takes a single local permit here).
     *
     * @param path the API path beginning with {@code '/'}
     * @return the raw JSON response body
     * @throws OpenDotaException on any HTTP error or transport failure
     */
    public String postJson(String path) throws OpenDotaException {
        if (!forwarding) {
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
        }

        String url = base + path;
        if (keyed) {
            url += (path.indexOf('?') >= 0 ? "&" : "?") + "api_key=" + encode(apiKey);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(forwarding ? FORWARDING_REQUEST_TIMEOUT : REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (forwardingToken != null) {
            builder.header(SIDECAR_TOKEN_HEADER, forwardingToken);
        }
        HttpRequest request = builder.build();

        try {
            HttpResponse<String> response = send(request);
            int status = response.statusCode();
            String body = response.body();
            if (status >= 200 && status <= 299) {
                return body;
            }
            String scrubbed = body;
            if (keyed && scrubbed != null) {
                scrubbed = scrubbed.replace(apiKey, "REDACTED").replace(encode(apiKey), "REDACTED");
            }
            throw new OpenDotaException(status, path, sanitizeUpstream(scrubbed));
        } catch (IOException e) {
            if (isResponseTooLarge(e)) {
                throw new OpenDotaException(0, path,
                        "upstream response exceeded " + maxResponseBytes + "-byte cap");
            }
            throw new OpenDotaException(0, path, "request failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenDotaException(0, path, "request interrupted", e);
        }
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
        } catch (Throwable t) {
            // Complete the future for EVERY failure type — crucially java.lang.Error (OOM/StackOverflow),
            // which a `catch (OpenDotaException | RuntimeException)` would miss, leaving the future
            // uncompleted and any follower parked in await() hung forever. Re-throw the original type so
            // an Error stays fatal on this thread rather than being masked. (The final wrap is unreachable
            // today — fetch() declares only OpenDotaException — but guards a future signature change.)
            mine.completeExceptionally(t);
            if (t instanceof OpenDotaException ode) {
                throw ode;
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new OpenDotaException(0, path, "unexpected error", t);
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
            // Re-throw the leader's Error/RuntimeException as-is so a follower sees the SAME failure the
            // leader did — an Error stays fatal here too, rather than being buried in a clean error
            // envelope while the leader thread dies fatally (an asymmetry worse than the original hang).
            if (cause instanceof Error err) {
                throw err;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new OpenDotaException(0, path,
                    "request failed: " + (cause == null ? "unknown" : cause.getClass().getSimpleName()), cause);
        }
    }

    /**
     * Perform the rate-limited HTTP GET, caching a 2xx body when {@code cacheable}.
     *
     * <p>Package-private (rather than {@code private}) solely so the single-flight follower-hang test can
     * subclass this client and make the leader's call fail with an {@link Error} — an outcome that cannot
     * be produced through the real HTTP path. Treat it as private otherwise.
     */
    String fetch(String path, String cacheKey, Duration ttl, boolean cacheable) throws OpenDotaException {
        String url = base + path;
        if (keyed) {
            // Encode the key so an operator-supplied value containing characters
            // illegal in a URL query cannot make URI.create throw out of getJson.
            url += (path.indexOf('?') >= 0 ? "&" : "?") + "api_key=" + encode(apiKey);
        }

        Duration requestTimeout = forwarding ? forwardingTimeoutFor(path) : requestTimeoutFor(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT);
        if (forwardingToken != null) {
            // Forwarding client → token-gated sidecar: present the shared secret.
            builder.header(SIDECAR_TOKEN_HEADER, forwardingToken);
        }
        HttpRequest request = builder.GET().build();

        // Direct (non-forwarding) GETs are idempotent, so a transient upstream failure is retried a few
        // times with exponential backoff: a 5xx always, and a timeout/connect drop only on a normally-fast
        // endpoint. A slow endpoint that already gets the extended timeout is NOT retried on timeout — the
        // extra time is its resilience; retrying a genuinely-slow query (e.g. /search) just doubles the wait.
        // Each attempt takes its own rate-limit permit, but the wait for one is bounded ACROSS the whole
        // call by a single deadline (below) rather than a fresh full budget per attempt — so a saturated
        // bucket plus retries can't stack up to maxAttempts × rateLimitBudget. Forwarding never retries
        // here (its refused-connection retry lives in send()).
        // Only meaningful for direct GETs (forwarding never retries here); a forwarding client's longer
        // timeout must not be read as a "slow endpoint" signal for the retry decisions below.
        boolean slow = !forwarding && requestTimeout.compareTo(REQUEST_TIMEOUT) > 0;
        int maxAttempts = forwarding ? 1 : 1 + MAX_UPSTREAM_RETRIES;
        Duration backoff = UPSTREAM_RETRY_BASE_BACKOFF;
        // One permit-wait deadline for the whole call: each attempt waits only until this, never a fresh
        // full budget. Bounds the call's CUMULATIVE permit-wait by rateLimitBudget (not total wall time —
        // backoff sleeps and per-request timeouts still add on top). In practice each retry's wait is about
        // one token-refill interval (~1s keyless / ~0.2s keyed), so even the worst case is a few seconds.
        long rateLimitDeadlineNanos = System.nanoTime() + rateLimitBudget.toNanos();
        for (int attempt = 1; ; attempt++) {
            // A forwarding client delegates rate limiting to the sidecar, so it must not consume a local
            // permit (its own limiter would wrongly throttle the loopback hop).
            if (!forwarding) {
                boolean acquired;
                try {
                    // tryAcquire degrades a <=0 wait to a single non-blocking try, so once the deadline
                    // passes a retry's acquire no longer blocks.
                    acquired = rateLimiter.tryAcquire(Duration.ofNanos(rateLimitDeadlineNanos - System.nanoTime()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new OpenDotaException(0, path, "request interrupted", ie);
                }
                if (!acquired) {
                    throw new OpenDotaException(429, path,
                            "client-side rate limit: no permit within " + rateLimitBudget.toSeconds() + "s");
                }
            }

            try {
                // A size-capped subscriber (rather than BodyHandlers.ofString) so a
                // hostile/oversized upstream body cannot exhaust the heap. The body is
                // still delivered through the client's subscriber pipeline, so the
                // request timeout continues to bound delivery.
                HttpResponse<String> response = send(request);
                int status = response.statusCode();
                String body = response.body();
                if (status >= 200 && status <= 299) {
                    if (cacheable && body != null) {
                        cache.put(cacheKey, body, ttl);
                    }
                    return body;
                }
                // Retry a transient server error (5xx) or an upstream rate-limit (429) on a direct GET
                // before surfacing it. A 429 is the canonical "back off and retry" signal, so honor the
                // server's Retry-After hint when present rather than the fixed exponential backoff.
                if (!forwarding && attempt < maxAttempts && (status == 429 || (status >= 500 && status <= 599))) {
                    backoff = status == 429 ? retryAfterBackoff(response, backoff, path)
                                            : retryBackoff(backoff, path);
                    continue;
                }
                // Scrub the actual key value (raw and URL-encoded) first, so an upstream
                // that echoes the credential in any shape (not just the api_key= query
                // form the pattern below covers) cannot leak it; then apply the generic
                // pattern redaction and length bound before it reaches the error envelope.
                String scrubbed = body;
                if (keyed && scrubbed != null) {
                    scrubbed = scrubbed.replace(apiKey, "REDACTED").replace(encode(apiKey), "REDACTED");
                }
                throw new OpenDotaException(status, path, sanitizeUpstream(scrubbed));
            } catch (IOException e) {
                if (isResponseTooLarge(e)) {
                    throw new OpenDotaException(0, path,
                            "upstream response exceeded " + maxResponseBytes + "-byte cap");
                }
                // Retry a transient transport failure on a direct GET. A refused/reset connection is
                // unrelated to query duration, so it is retried even on the slow endpoints; a *timeout*
                // is retried only on a normally-fast endpoint, since a slow endpoint's extended timeout
                // is its resilience and re-issuing a genuinely-slow query just doubles the wait.
                if (!forwarding && attempt < maxAttempts && isRetryableTransport(e, slow)) {
                    backoff = retryBackoff(backoff, path);
                    continue;
                }
                // Do NOT include the URL/api_key in the message.
                throw new OpenDotaException(0, path, "request failed: " + e.getClass().getSimpleName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenDotaException(0, path, "request interrupted", e);
            }
        }
    }

    /**
     * The request timeout for a path: extended for OpenDota's slow endpoints — {@code /search}'s
     * full-text query and {@code /explorer}'s ad-hoc SQL both routinely exceed the default.
     */
    private static Duration requestTimeoutFor(String path) {
        if (path == null) {
            return REQUEST_TIMEOUT;
        }
        int q = path.indexOf('?');
        String p = q >= 0 ? path.substring(0, q) : path;
        return p.startsWith("/search") || p.startsWith("/explorer") ? SLOW_REQUEST_TIMEOUT : REQUEST_TIMEOUT;
    }

    /**
     * The request timeout a forwarding client applies to the loopback sidecar hop, mirroring the slow-path
     * split of {@link #requestTimeoutFor} but at the larger forwarding scale (the sidecar's slow endpoints
     * take its extended upstream timeout, which the loopback wait must outlast). See
     * {@link #FORWARDING_REQUEST_TIMEOUT}.
     */
    private static Duration forwardingTimeoutFor(String path) {
        if (path == null) {
            return FORWARDING_REQUEST_TIMEOUT;
        }
        int q = path.indexOf('?');
        String p = q >= 0 ? path.substring(0, q) : path;
        return p.startsWith("/search") || p.startsWith("/explorer")
                ? FORWARDING_SLOW_REQUEST_TIMEOUT : FORWARDING_REQUEST_TIMEOUT;
    }

    /**
     * Whether a transport failure on an idempotent GET is worth retrying. A refused/reset connection
     * ({@link ConnectException}) always is; a {@link HttpTimeoutException} only on a normally-fast
     * endpoint ({@code !slow}) — a slow endpoint already carries the extended timeout as its resilience.
     */
    private static boolean isRetryableTransport(Throwable t, boolean slow) {
        boolean timedOut = false;
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ConnectException) {
                return true;
            }
            if (c instanceof HttpTimeoutException) {
                timedOut = true;
            }
        }
        return timedOut && !slow;
    }

    /** Sleep the current backoff (interrupt-aware), returning the next (doubled, capped) backoff. */
    private Duration retryBackoff(Duration backoff, String path) throws OpenDotaException {
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OpenDotaException(0, path, "request interrupted", ie);
        }
        Duration next = backoff.multipliedBy(2);
        return next.compareTo(UPSTREAM_RETRY_MAX_BACKOFF) > 0 ? UPSTREAM_RETRY_MAX_BACKOFF : next;
    }

    /**
     * Backoff after an upstream {@code 429}: sleep the server's {@code Retry-After} hint (delta-seconds
     * form, capped by {@link #MAX_RETRY_AFTER_BACKOFF}) when present and numeric, else fall back to the
     * standard exponential {@link #retryBackoff}. Returns the next backoff for any subsequent retry.
     */
    private Duration retryAfterBackoff(HttpResponse<String> response, Duration backoff, String path)
            throws OpenDotaException {
        Duration hint = response.headers().firstValue("Retry-After")
                .map(OpenDotaClient::parseRetryAfterSeconds)
                .orElse(null);
        if (hint == null) {
            return retryBackoff(backoff, path);
        }
        Duration wait = hint.compareTo(MAX_RETRY_AFTER_BACKOFF) > 0 ? MAX_RETRY_AFTER_BACKOFF : hint;
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OpenDotaException(0, path, "request interrupted", ie);
        }
        return backoff;   // leave the exponential schedule unchanged for any further retry
    }

    /** A {@code Retry-After} delta-seconds value as a Duration; null for missing/negative/non-numeric (HTTP-date). */
    private static Duration parseRetryAfterSeconds(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notDeltaSeconds) {
            return null;   // HTTP-date form is not honored; caller falls back to exponential backoff
        }
    }

    /**
     * Send the request through the shared client. A forwarding client retries a
     * <em>refused</em> connection (the sidecar may still be binding its port) within
     * {@link #SIDECAR_RETRY_BUDGET}, backing off exponentially; once the budget is
     * spent the {@link ConnectException} propagates and maps to a transport error.
     * Non-forwarding clients send exactly once.
     */
    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        if (!forwarding) {
            return httpClient.send(request, info -> new CappedBodySubscriber(maxResponseBytes));
        }
        long deadlineNanos = System.nanoTime() + SIDECAR_RETRY_BUDGET.toNanos();
        long backoffMillis = SIDECAR_RETRY_BASE_BACKOFF.toMillis();
        while (true) {
            try {
                return httpClient.send(request, info -> new CappedBodySubscriber(maxResponseBytes));
            } catch (ConnectException ce) {
                long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMillis <= 0L) {
                    throw ce;
                }
                Thread.sleep(Math.min(backoffMillis, remainingMillis));
                backoffMillis = Math.min(backoffMillis * 2, SIDECAR_RETRY_MAX_BACKOFF.toMillis());
            }
        }
    }

    /** True if {@code t} or any of its causes is a {@link ResponseTooLargeException}. */
    private static boolean isResponseTooLarge(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ResponseTooLargeException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Redact credential echoes and bound the length of an upstream response body
     * before it is surfaced (via {@link OpenDotaException}) into the error envelope
     * returned to the MCP client. Applied only to error (non-2xx) bodies; the 2xx
     * raw-JSON passthrough is never altered. A hostile upstream that echoes the
     * inbound request URL therefore cannot leak the appended {@code api_key}, and
     * an oversized error body cannot be copied wholesale into the envelope.
     */
    static String sanitizeUpstream(String body) {
        if (body == null) {
            return null;
        }
        String redacted = body.replaceAll("(?i)api_key=[^&\\s\"]*", "api_key=REDACTED");
        if (redacted.length() > MAX_UPSTREAM_SNIPPET) {
            redacted = redacted.substring(0, MAX_UPSTREAM_SNIPPET) + "...(truncated)";
        }
        return redacted;
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

    /** An immutable snapshot of this client's cache and rate-limiter counters, for a stats endpoint. */
    public record Stats(boolean keyed, long cacheHits, long cacheMisses, int cacheEntries,
                        long cacheBytes, long availablePermits, int permitsPerMinute) {
    }

    /**
     * Snapshot the cache hit/miss tallies and the rate limiter's available permits. Meaningful for
     * a direct client (the sidecar's shared client); a forwarding client bypasses both, so its
     * counters stay at zero.
     */
    public Stats stats() {
        return new Stats(keyed, cache.hits(), cache.misses(), cache.size(), cache.approximateBytes(),
                (long) Math.floor(rateLimiter.availablePermits()), rateLimiter.permitsPerMinute());
    }

    /** URL-encode a query/path value using UTF-8. */
    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Resolve the cache TTL for a request path. The query string (if any) is
     * stripped before prefix matching, and more-specific prefixes are checked
     * before more-general ones.
     *
     * <p>Public so a co-located caller (the sidecar's durable cache) can reuse the resolved
     * per-path horizon as a single source of truth rather than duplicating this table. Drift-guarded:
     * edit only the root copy and mirror via {@code scripts/sync-client-copies.sh}.
     */
    public Duration ttlFor(String path) {
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
        if (p.startsWith("/heroes/")) {
            // /heroes/{id}/* (matchups, durations, itemPopularity, players, matches) are rolling
            // aggregates over recent matches — far more volatile than the static /heroes list, so a
            // short horizon. MUST precede the bare /heroes check below (which would else swallow these).
            return Duration.ofSeconds(60);
        }
        if (p.startsWith("/heroes")) {
            return Duration.ofHours(6);
        }
        if (p.startsWith("/constants/")) {
            return Duration.ofHours(6);
        }
        if (p.startsWith("/benchmarks")) {
            // Percentile benchmarks over recent matches; a slow-moving aggregate like heroStats.
            return Duration.ofHours(1);
        }
        if (p.startsWith("/distributions")) {
            // MMR/rank distributions across the player base; change very slowly.
            return Duration.ofHours(6);
        }
        if (p.startsWith("/schema")) {
            // The /explorer SQL schema (table/column listing) is near-static reference data.
            return Duration.ofHours(24);
        }
        if (p.startsWith("/explorer")) {
            // Ad-hoc SQL: each distinct query is a unique cache key, and /explorer returns HTTP 200
            // even on a SQL error ({err} in the body) — caching would pin those error bodies.
            // Uncacheable (Duration.ZERO also disables single-flight for it).
            return Duration.ZERO;
        }
        if (p.startsWith("/proMatches") || p.startsWith("/publicMatches")) {
            return Duration.ofSeconds(45);
        }
        if (p.startsWith("/proPlayers") || p.startsWith("/topPlayers")) {
            // Pro roster / top-ladder rankings move slowly (not live data).
            return Duration.ofHours(6);
        }
        if (p.startsWith("/teams") || p.startsWith("/leagues")) {
            // A team/league's recent-matches feed is rolling; the team/league profile itself moves slowly.
            return p.endsWith("/matches") ? Duration.ofSeconds(60) : Duration.ofHours(1);
        }
        if (p.startsWith("/live") || p.startsWith("/health") || p.startsWith("/request")) {
            // Live game feed / API health snapshot / a parse job's polled status — never cache.
            return Duration.ZERO;
        }
        if (p.startsWith("/records")) {
            // All-time record leaderboards move very slowly.
            return Duration.ofHours(1);
        }
        if (p.startsWith("/parsedMatches")) {
            // The rolling public parse feed.
            return Duration.ofSeconds(60);
        }
        if (p.startsWith("/metadata")) {
            // Site-wide patch/banner metadata; semi-static.
            return Duration.ofMinutes(5);
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

    /** Signals that an upstream body exceeded the configured size cap. */
    private static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * A {@link BodySubscriber} that accumulates the response into memory but aborts
     * once more than {@code cap} bytes have arrived, cancelling the subscription and
     * failing with {@link ResponseTooLargeException}. Used in place of
     * {@code BodyHandlers.ofString()} so an oversized upstream response is bounded
     * to roughly {@code cap} bytes rather than buffered in full.
     */
    private static final class CappedBodySubscriber implements BodySubscriber<String> {

        private final long cap;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private long total;
        private Flow.Subscription subscription;

        CappedBodySubscriber(long cap) {
            this.cap = cap;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer b : buffers) {
                int remaining = b.remaining();
                total += remaining;
                if (total > cap) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new ResponseTooLargeException("upstream response exceeded " + cap + "-byte cap"));
                    return;
                }
                byte[] chunk = new byte[remaining];
                b.get(chunk);
                buffer.write(chunk, 0, remaining);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toString(StandardCharsets.UTF_8));
        }
    }
}
