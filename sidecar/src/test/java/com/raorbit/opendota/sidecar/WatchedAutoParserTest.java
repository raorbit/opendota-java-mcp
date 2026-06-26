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

        FakeClient() {
            super(null);   // public ctor; getJson/postJson are overridden so no HTTP happens
        }

        @Override
        public String getJson(String path) throws OpenDotaException {
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
        try (WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L), 3_600_000L)) {
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
        try (WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L), 3_600_000L)) {
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
        try (WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L, 6L), 3_600_000L)) {
            parser.pollOnce();
            // Only the two unparsed matches (11, 20) were requested; the parsed one (10) was skipped.
            assertThat(client.posts).containsExactlyInAnyOrder("/request/11", "/request/20");
            assertThat(parser.parseRequested()).isEqualTo(2);
        }
    }

    @Test
    void pollOnceContinuesPastAPlayerWhoseListingFails() {
        FakeClient client = new FakeClient();
        // Player 5's listing is absent (getJson 404s); player 6's succeeds with one unparsed match.
        client.bodies.put("/players/6/matches?project=version&limit=100",
                "[{\"match_id\":20,\"version\":null}]");
        try (WatchedAutoParser parser = new WatchedAutoParser(client, Set.of(5L, 6L), 3_600_000L)) {
            parser.pollOnce();
            assertThat(client.posts).containsExactly("/request/20");
        }
    }
}
