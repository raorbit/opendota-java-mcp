package com.raorbit.opendota.sidecar;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Requests OpenDota parses of <em>watched-player</em> matches so the durable archive ends up holding
 * fully-parsed bodies (spec §6.5). This is the one place the sidecar issues a write
 * ({@code POST /request/{match_id}}) on its own initiative — it is active only when watched players are
 * configured and {@code OPENDOTA_SIDECAR_L2_WATCHED_AUTO_PARSE} is on (the default when watching).
 *
 * <p>Two complementary triggers, sharing one dedup set so a match is requested at most once per process:
 * <ul>
 *   <li><b>Access-driven</b> ({@link #requestParseAsync}): when {@link L2CachingGateway} fetches a watched
 *       match that is still unparsed, it asks here for a parse. The gateway's hourly re-check then
 *       upgrades the archived row in place once OpenDota finishes parsing.</li>
 *   <li><b>Proactive poll</b> ({@link #pollOnce}, scheduled by {@link #start}): periodically lists each
 *       watched player's recent matches and requests a parse for any that are unparsed — so a match is
 *       parsed even if no agent ever fetches it by id.</li>
 * </ul>
 *
 * <p>Best-effort throughout: a failed parse request (rate limit, transport) is counted and dropped from
 * the dedup set so a later poll can retry; it never propagates to the request that triggered it. The
 * access-driven path is fire-and-forget: {@link #requestParseAsync} does only the cheap dedup add on the
 * caller (request-serving) thread and runs the actual POST — whose rate-limit park can be seconds — on a
 * single daemon executor, so a {@code GET} never blocks on this. {@link #requestParse} itself is
 * synchronous (POST on the calling thread); it is used by the off-thread poll and by unit tests, never on
 * the request-serving path. Parsing of the player-matches list is a dependency-free regex scan (the
 * sidecar has no JSON library), mirroring {@link L2CachingGateway#isParsedMatch}.
 *
 * <p>Note: OpenDota can only parse matches whose replays still exist (roughly the last couple of weeks),
 * and the poll scans only each player's most recent {@value #POLL_MATCH_LIMIT} matches, so "all matches"
 * means all <em>currently-parseable, recent</em> matches — not a backfill of a player's entire history.
 */
public final class WatchedAutoParser implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WatchedAutoParser.class.getName());

    /** Recent matches per watched player scanned on each poll (older replays have expired and can't parse). */
    private static final int POLL_MATCH_LIMIT = 100;
    /**
     * Upper bound on the dedup set so it can't grow without limit over the process lifetime. Successful
     * requests are kept (never pruned on success), so a long-lived sidecar would otherwise accumulate one
     * entry per watched match ever seen; once past this bound the set is cleared. A clear costs at most a
     * duplicate parse request (idempotent upstream), never correctness, and the bound is far above any
     * realistic count of parseable recent matches for a watched-player set.
     */
    private static final int MAX_REQUESTED_TRACKED = 100_000;
    /** Delay before the first poll, so the sidecar finishes starting before the first sweep. */
    private static final long INITIAL_POLL_DELAY_MILLIS = 15_000L;

    /** One match object in a projected {@code /players/{id}/matches} array (flat — no nested braces). */
    private static final Pattern MATCH_OBJECT = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern MATCH_ID_FIELD = Pattern.compile("\"match_id\"\\s*:\\s*(\\d+)");
    /** An unparsed match: {@code "version": null} (a numeric version means already parsed). */
    private static final Pattern UNPARSED_VERSION = Pattern.compile("\"version\"\\s*:\\s*null");

    private final OpenDotaClient client;
    private final Set<Long> watchedIds;
    private final long pollIntervalMillis;
    /** Match ids already requested this process, so neither trigger asks twice (bounded — see {@link #boundRequested}). */
    private final Set<Long> requested = ConcurrentHashMap.newKeySet();

    private final AtomicLong parseRequested = new AtomicLong();
    private final AtomicLong parseError = new AtomicLong();

    /** Single daemon thread that runs the access-driven (fire-and-forget) parse POSTs off the caller thread. */
    private final ExecutorService parseExecutor;

    private ScheduledExecutorService scheduler;

    /**
     * @param client             the sidecar's direct client (holds the API key; its {@code postJson} issues
     *                           the real {@code POST /request/{id}})
     * @param watchedIds         the watched Steam32 account ids to poll (a defensive copy is taken)
     * @param pollIntervalMillis cadence of the proactive poll
     */
    public WatchedAutoParser(OpenDotaClient client, Set<Long> watchedIds, long pollIntervalMillis) {
        this.client = client;
        this.watchedIds = Set.copyOf(watchedIds);
        this.pollIntervalMillis = pollIntervalMillis;
        this.parseExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "watched-parse-exec");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Request a parse of one match <em>synchronously</em> (the POST runs on the calling thread), at most
     * once per process. A failed request is dropped from the dedup set so the poll can retry it later.
     * Never throws. Used by the off-thread {@link #pollOnce} sweep and by unit tests; the access-driven
     * request-serving path uses {@link #requestParseAsync} instead so a GET never blocks on the POST.
     */
    public void requestParse(long matchId) {
        if (!requested.add(matchId)) {
            return;   // already requested (or another thread is requesting it now)
        }
        boundRequested();
        doParse(matchId);
    }

    /**
     * Request a parse of one match <em>fire-and-forget</em> (the access-driven trigger), at most once per
     * process. Only the cheap, non-blocking dedup add runs on the caller (request-serving) thread; the
     * actual POST — whose rate-limit park can be seconds — is submitted to a single daemon executor so a
     * GET never blocks on it. A failed request is dropped from the dedup set so the poll can retry it
     * later. Never throws.
     */
    public void requestParseAsync(long matchId) {
        if (!requested.add(matchId)) {
            return;   // already requested (or another thread is requesting it now)
        }
        boundRequested();
        try {
            parseExecutor.execute(() -> doParse(matchId));
        } catch (RejectedExecutionException e) {
            // The executor is shutting down (close() raced this request): undo the dedup add so a later
            // poll can retry, and drop the request silently — there is nothing to count as a parse error.
            requested.remove(matchId);
            LOG.log(Level.FINE, e, () -> "watched auto-parse async submit rejected for match " + matchId);
        }
    }

    /**
     * Issue the actual {@code POST /request/{id}} and update the counters, dropping the id from the dedup
     * set on failure so the poll can retry. Runs on whichever thread {@link #requestParse} (caller/poll) or
     * {@link #requestParseAsync} (the parse executor) hands it. Never throws.
     */
    /** Clear the dedup set if it has grown past {@link #MAX_REQUESTED_TRACKED}, bounding process-lifetime
     *  memory. Rare enough that the worst case — a duplicate, idempotent parse request — is negligible. */
    private void boundRequested() {
        if (requested.size() > MAX_REQUESTED_TRACKED) {
            requested.clear();
        }
    }

    private void doParse(long matchId) {
        try {
            client.postJson("/request/" + matchId);
            parseRequested.incrementAndGet();
        } catch (OpenDotaException | RuntimeException e) {
            parseError.incrementAndGet();
            requested.remove(matchId);   // allow a later retry of a failed request
            LOG.log(Level.FINE, e, () -> "watched auto-parse request failed for match " + matchId);
        }
    }

    /**
     * One proactive sweep: for each watched player, list their recent matches and request a parse for any
     * that are unparsed. Best-effort per player — a failure for one player is logged and the sweep
     * continues with the next. Package-visible so a test can drive a single deterministic sweep without
     * the scheduler.
     */
    void pollOnce() {
        for (Long id : watchedIds) {
            String path = "/players/" + id + "/matches?project=version&limit=" + POLL_MATCH_LIMIT;
            try {
                for (long matchId : unparsedMatchIds(client.getJson(path))) {
                    requestParse(matchId);
                }
            } catch (OpenDotaException e) {
                LOG.log(Level.FINE, e, () -> "watched auto-parse poll could not list matches for player " + id);
            }
        }
    }

    /**
     * Extract the {@code match_id}s of unparsed matches from a projected {@code /players/{id}/matches}
     * body: an object with a {@code match_id} and {@code "version": null}. Dependency-free regex; objects
     * are flat under {@code project=version}. Returns an empty list for {@code null}/empty input.
     */
    static List<Long> unparsedMatchIds(String body) {
        List<Long> ids = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return ids;
        }
        Matcher objects = MATCH_OBJECT.matcher(body);
        while (objects.find()) {
            String object = objects.group();
            Matcher id = MATCH_ID_FIELD.matcher(object);
            if (id.find() && UNPARSED_VERSION.matcher(object).find()) {
                try {
                    ids.add(Long.parseLong(id.group(1)));
                } catch (NumberFormatException ignored) {
                    // a match_id too large to parse — skip it
                }
            }
        }
        return ids;
    }

    /**
     * Start the proactive poll on a single daemon thread (no-op when there are no watched players or it is
     * already running). The access-driven {@link #requestParse} works without this; only the background
     * sweep needs the scheduler, so unit tests can exercise {@link #pollOnce} directly and never spawn a
     * thread.
     */
    public synchronized void start() {
        if (watchedIds.isEmpty() || scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watched-parse-poll");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollSafely, INITIAL_POLL_DELAY_MILLIS, pollIntervalMillis,
                TimeUnit.MILLISECONDS);
        LOG.info(() -> "watched auto-parse poll started for " + watchedIds.size() + " player(s) every "
                + pollIntervalMillis + "ms");
    }

    /**
     * Run one sweep, swallowing any error so the scheduled task is never cancelled by an exception.
     * Package-visible so a test can assert it absorbs an unchecked failure from the underlying listing
     * (one that escapes {@link #pollOnce}'s per-player {@link OpenDotaException} catch).
     */
    void pollSafely() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> "watched auto-parse poll sweep failed");
        }
    }

    /** Number of parse requests successfully issued. */
    public long parseRequested() {
        return parseRequested.get();
    }

    /** Number of parse requests that failed (rate limit / transport); each is eligible for a later retry. */
    public long parseErrors() {
        return parseError.get();
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        parseExecutor.shutdownNow();
    }
}
