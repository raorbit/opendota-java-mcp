package com.raorbit.opendota.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 8 (spec §10): {@link L2CachingGateway#classify} maps each §4 prefix to its expected class,
 * strips query strings, requires a digits-only id for {@code /matches/{id}}, and falls a
 * {@code /matches/<non-id>} shape to NO_STORE. Also covers the parse-gate probe (§5.1).
 */
class L2ClassifyTest {

    @ParameterizedTest
    @CsvSource({
            // /matches/{id} — digits only -> PERMANENT
            "/matches/123,                         PERMANENT",
            "/matches/8000000001,                  PERMANENT",
            "/matches/123?foo=bar,                 PERMANENT",   // query stripped first
            // /matches/<non-id> shapes -> NO_STORE (so a future subpath can't be mis-stored)
            "/matches/123/something,               NO_STORE",
            "/matches/abc,                         NO_STORE",
            "/matches/,                            NO_STORE",
            "/matches,                             NO_STORE",
            // static reference data -> PERMANENT (patch-scoped)
            "/heroes,                              PERMANENT",
            // /heroes/{id}/* are rolling aggregates, not static -> TTL (never pinned as PERMANENT)
            "/heroes/14,                           TTL",
            "/heroes/14/matches,                   TTL",
            "/heroes/14/matchups,                  TTL",
            "/heroStats,                           PERMANENT",
            "/heroStats?foo=1,                     PERMANENT",
            "/constants/items,                     PERMANENT",
            "/constants/patch,                     PERMANENT",
            // volatile feeds -> TTL (not stored in v1)
            "/players/123,                         TTL",
            "/players/123/recentMatches,           TTL",
            "/proMatches,                          TTL",
            "/proMatches?less_than_match_id=5,     TTL",
            "/publicMatches,                       TTL",
            "/rankings,                            TTL",
            "/search,                              TTL",
            "/search?q=dendi,                      TTL",
            // rolling aggregates / drifting histogram -> TTL (volatile, not durably pinned)
            "/benchmarks?hero_id=14,               TTL",
            "/distributions,                       TTL",
            // live + unrecognised -> NO_STORE
            "/live,                                NO_STORE",
            "/totally-unknown,                     NO_STORE",
            "/,                                    NO_STORE"
    })
    void classifyMapsPrefixesToExpectedClass(String path, Classification expected) {
        assertThat(L2CachingGateway.classify(path.trim())).isEqualTo(expected);
    }

    @Test
    void classifyOfNullIsNoStore() {
        assertThat(L2CachingGateway.classify(null)).isEqualTo(Classification.NO_STORE);
    }

    @Test
    void parseGateRecognisesParsedMatch() {
        // version is a non-null number AND a corroborating parse field is present.
        assertThat(L2CachingGateway.isParsedMatch(
                "{\"match_id\":1,\"version\":21,\"od_data\":{\"x\":1}}")).isTrue();
        assertThat(L2CachingGateway.isParsedMatch(
                "{\"version\":21,\"objectives\":[{\"type\":\"tower\"}]}")).isTrue();
        assertThat(L2CachingGateway.isParsedMatch(
                "{\"version\":21,\"players\":[{\"purchase_log\":[]}]}")).isTrue();
    }

    @Test
    void parseGateRejectsUnparsedMatch() {
        // version null -> unparsed.
        assertThat(L2CachingGateway.isParsedMatch("{\"match_id\":1,\"version\":null}")).isFalse();
        // version absent -> unparsed.
        assertThat(L2CachingGateway.isParsedMatch("{\"match_id\":1,\"radiant_win\":true}")).isFalse();
        // version present but NO corroborating field -> not confidently parsed (defence in depth).
        assertThat(L2CachingGateway.isParsedMatch("{\"match_id\":1,\"version\":21}")).isFalse();
        // version present but the only corroborator is explicitly null -> NOT parsed (purchase_log
        // must be non-null, like the other corroborators; a bare contains() would wrongly accept this).
        assertThat(L2CachingGateway.isParsedMatch("{\"version\":21,\"purchase_log\":null}")).isFalse();
        // null body.
        assertThat(L2CachingGateway.isParsedMatch(null)).isFalse();
    }

    @Test
    void parseGateIgnoresWhitespaceAndKeyOrder() {
        assertThat(L2CachingGateway.isParsedMatch(
                "{\n  \"od_data\" : {\"a\":1},\n  \"version\"  :  7\n}")).isTrue();
    }

    @Test
    void latestPatchIdPicksHighestId() {
        assertThat(L2CachingGateway.latestPatchId(
                "[{\"id\":53,\"name\":\"7.36\"},{\"id\":54,\"name\":\"7.37\"},{\"id\":52}]"))
                .isEqualTo("54");
        assertThat(L2CachingGateway.latestPatchId("[]")).isNull();
        assertThat(L2CachingGateway.latestPatchId(null)).isNull();
    }
}
