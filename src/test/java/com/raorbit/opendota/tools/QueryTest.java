package com.raorbit.opendota.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTest {

    @Test
    void appendUsesQuestionThenAmpersandAndUrlEncodes() {
        StringBuilder sb = new StringBuilder("/publicMatches");
        boolean[] started = {false};
        Query.append(sb, started, "min_rank", 50);
        Query.append(sb, started, "note", "a b&c=d");   // encoder makes it injection-safe
        assertThat(sb.toString()).isEqualTo("/publicMatches?min_rank=50&note=a+b%26c%3Dd");
    }

    @Test
    void appendSkipsNullWithoutConsumingTheQuestionMark() {
        StringBuilder sb = new StringBuilder("/teams");
        boolean[] started = {false};
        Query.append(sb, started, "page", null);
        assertThat(sb.toString()).isEqualTo("/teams");
    }

    @Test
    void appendRepeatedExpandsCsvAndDropsBlankTokens() {
        StringBuilder sb = new StringBuilder("/players/1/matches");
        boolean[] started = {false};
        Query.appendRepeated(sb, started, "project", " kills , , deaths ");
        assertThat(sb.toString()).isEqualTo("/players/1/matches?project=kills&project=deaths");
    }
}
