package com.raorbit.opendota.sidecar;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link WatchedAutoParser}: the unparsed-match regex extraction, the once-per-match
 * dedup of parse requests with retry-on-failure, and a single proactive {@link WatchedAutoParser#pollOnce}
 * sweep (driven directly, without the scheduler).
 */
class WatchedAutoParserTest {

    /** A fake client that serves canned GET bodies and records (or fails) POSTs — no real transport. */
    private static final class FakeClient extends OpenDotaClient {
        final Map<String, String> bodies = new ConcurrentHashMap<>();
        final List<String> posts = new CopyOnWriteArrayList<>();
        volatile boolean failPosts;
        /** When set, getJson throws an unchecked RuntimeException (NOT OpenDotaException) so it escapes
         *  pollOnce's per-player OpenDotaException catch and reaches pollSafely. */
        volatile boolean throwUncheckedOnGet;

        FakeClient() {
            super(null);   // public ctor; getJson/postJson are overridden so no HTTP happens
        }

        @Override
        public String getJson(String path) throws OpenDotaException {
            if (throwUncheckedOnGet) {
                throw new IllegalStateException("boom from getJson for " + path);
            }
            String body = bodies.get(path);
            if (body == null) {
                throw new OpenDotaException(404, path, "{\"error\":\"not found\"}");
            }
            return body;
        }

        @Override
        public String postJson(String path) throws OpenDotaException {
            if (failPosts) {
                throw new OpenDotaException(429, path, "rate limited");
            }
            posts.add(path);
            return "{\"job\":{\"jobId\":1}}";
        }
    }

    @Test
    void unparsedMatchIdsPicksOnlyNullVersionMatches() {
        String body = "[{\"match_id\":1,\"version\":21},{\"match_id\":2,\"version\":null},"
                + "{\"match_id\":3,\"version\": null},{\"match_id\":4,\"version\":7}]";
        assertThat(WatchedAutoParser.unparsedMatchIds(body)).containsExactly(2L, 3L);
    }

    @Test
    void unparsedMatchIdsHandlesNullAndEmpty() {
        assertThat(WatchedAutoParser.unparsedMatchIds(null)).isEmpty();
        assertThat(WatchedAutoParser.unparsedMatchIds("")).isEmpty();
        assertThat(WatchedAutoParser.unparsedMatchIds("[]")).isEmpty();
        // All parsed → nothing to request.
        assertThat(WatchedAutoParser.unparsedMatchIds("[{\"match_id\":9,\"version\":1}]")).isEmpty();
    }

    @Test
    void requestParseIsDedupedToOncePerMatch() {
        FakeClient client = new FakeClient();
        try (client; WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L), 3_600_000L)) {
            parser.requestParse(777L);
            parser.requestParse(777L);   // deduped — no second POST
            assertThat(client.posts).containsExactly("/request/777");
            assertThat(parser.parseRequested()).isEqualTo(1);
            assertThat(parser.parseErrors()).isZero();
        }
    }

    @Test
    void failedParseRequestIsCountedAndRetryable() {
        FakeClient client = new FakeClient();
        client.failPosts = true;
        try (client; WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L), 3_600_000L)) {
            parser.requestParse(777L);
            assertThat(parser.parseErrors()).isEqualTo(1);
            assertThat(parser.parseRequested()).isZero();
            assertThat(client.posts).isEmpty();

            // The failed match was dropped from the dedup set, so a later (now-succeeding) attempt retries.
            client.failPosts = false;
            parser.requestParse(777L);
            assertThat(client.posts).containsExactly("/request/777");
            assertThat(parser.parseRequested()).isEqualTo(1);
        }
    }

    @Test
    void pollOnceRequestsParsesForEachWatchedPlayersUnparsedMatches() {
        FakeClient client = new FakeClient();
        client.bodies.put("/players/5/matches?project=version&limit=100",
                "[{\"match_id\":10,\"version\":21},{\"match_id\":11,\"version\":null}]");
        client.bodies.put("/players/6/matches?project=version&limit=100",
                "[{\"match_id\":20,\"version\":null}]");
        try (client; WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L, 6L), 3_600_000L)) {
            parser.pollOnce();
            // Only the two unparsed matches (11, 20) were requested; the parsed one (10) was skipped.
            assertThat(client.posts).containsExactlyInAnyOrder("/request/11", "/request/20");
            assertThat(parser.parseRequested()).isEqualTo(2);
        }
    }

    @Test
    void pollOnceCapsParseRequestsPerSweepAndLaterSweepsDrainTheBacklog() {
        FakeClient client = new FakeClient();
        int backlog = WatchedAutoParser.MAX_PARSE_REQUESTS_PER_SWEEP + 5;
        StringBuilder body = new StringBuilder("[");
        for (int i = 1; i <= backlog; i++) {
            if (i > 1) {
                body.append(',');
            }
            body.append("{\"match_id\":").append(i).append(",\"version\":null}");
        }
        body.append(']');
        client.bodies.put("/players/5/matches?project=version&limit=100", body.toString());
        try (client; WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L), 3_600_000L)) {
            // One sweep issues at most the cap — a fresh backlog must not burn a burst of ~10x-charged
            // POSTs competing with agent GETs for rate permits.
            parser.pollOnce();
            assertThat(client.posts).hasSize(WatchedAutoParser.MAX_PARSE_REQUESTS_PER_SWEEP);

            // The next sweep drains the remainder: already-requested ids are deduped and do NOT
            // consume the cap, so the 5 left-overs all go out.
            parser.pollOnce();
            assertThat(client.posts).hasSize(backlog);
            assertThat(parser.parseRequested()).isEqualTo(backlog);
        }
    }

    @Test
    void pollOnceContinuesPastAPlayerWhoseListingFails() {
        FakeClient client = new FakeClient();
        // Player 5's listing is absent (getJson 404s); player 6's succeeds with one unparsed match.
        client.bodies.put("/players/6/matches?project=version&limit=100",
                "[{\"match_id\":20,\"version\":null}]");
        try (client; WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L, 6L), 3_600_000L)) {
            parser.pollOnce();
            assertThat(client.posts).containsExactly("/request/20");
        }
    }

    @Test
    void constructorDoesNotStartPollThreadStartCreatesItCloseStopsIt() throws Exception {
        FakeClient client = new FakeClient();
        WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L), 3_600_000L);
        try (client) {
            // The constructor must not spawn the poll thread — only start() does.
            assertThat(pollThreadPresent()).as("no poll thread before start()").isFalse();

            parser.start();
            // INITIAL_POLL_DELAY is 15s, so the thread exists (scheduled) but pollOnce won't fire here.
            for (int i = 0; i < 50 && !pollThreadPresent(); i++) {
                Thread.sleep(20);
            }
            assertThat(pollThreadPresent()).as("start() creates the poll thread").isTrue();

            parser.close();
            // shutdownNow() interrupts the daemon; give it a brief moment to terminate, then assert it's gone.
            for (int i = 0; i < 50 && pollThreadPresent(); i++) {
                Thread.sleep(20);
            }
            assertThat(pollThreadPresent()).as("close() stops the poll thread").isFalse();
        }
    }

    /** True if a daemon thread named "watched-parse-poll" (the scheduler thread) is currently alive. */
    private static boolean pollThreadPresent() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> "watched-parse-poll".equals(t.getName()) && t.isAlive());
    }

    @Test
    void pollSafelySwallowsUncheckedFailureFromListing() {
        FakeClient client = new FakeClient();
        client.throwUncheckedOnGet = true;   // getJson throws an unchecked RuntimeException, escaping pollOnce
        try (client; WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L), 3_600_000L)) {
            // pollSafely() must absorb the RuntimeException so the scheduled task is never cancelled.
            assertThatCode(parser::pollSafely).doesNotThrowAnyException();
            assertThat(client.posts).isEmpty();
        }
    }
}
