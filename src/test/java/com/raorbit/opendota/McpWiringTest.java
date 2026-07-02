package com.raorbit.opendota;

import com.raorbit.opendota.client.OpenDotaClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Wiring smoke test. The MCP server itself is disabled so the context loads
 * without attaching the stdio transport to {@code System.in}; the test still
 * exercises the application's own bean graph (the {@link OpenDotaClient} and the
 * {@link ToolCallbackProvider} over all three tool classes).
 */
@SpringBootTest(properties = "spring.ai.mcp.server.enabled=false")
class McpWiringTest {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoadsAndExposesEveryTool() {
        assertThat(context.getBean(OpenDotaClient.class)).isNotNull();

        ToolCallbackProvider provider = context.getBean(ToolCallbackProvider.class);
        List<String> names = Arrays.stream(provider.getToolCallbacks())
                .map(cb -> cb.getToolDefinition().name())
                .toList();

        // Every read @Tool must be registered. This lists ALL of them (not a subset): combined with the
        // exact-count assertion below it is an exhaustiveness guard, so dropping a tool's @Tool annotation
        // (or adding a new one without updating this test) fails the build instead of silently changing the
        // tools/list surface. Grouped by tool class.
        assertThat(names).contains(
                // PlayerTools (15)
                "search_players", "get_player", "get_player_wl", "get_player_recent_matches",
                "get_player_peers", "get_player_totals", "get_player_matches", "get_player_heroes",
                "get_player_ratings", "get_player_rankings", "get_player_counts", "get_player_histograms",
                "get_player_pros", "get_player_wardmap", "get_player_wordcloud",
                // MatchTools (4)
                "get_match", "get_pro_matches", "get_public_matches", "get_live_games",
                // HeroTools (11)
                "list_heroes", "get_hero_stats", "get_hero_rankings", "get_benchmarks",
                "get_hero_matchups", "get_hero_item_popularity", "get_hero_durations",
                "get_hero_players", "get_hero_matches", "get_distributions", "get_constants",
                // LeagueTools (4)
                "get_league", "get_league_matches", "get_leagues", "get_league_teams",
                // ExplorerTools (2)
                "run_sql_explorer", "get_sql_schema",
                // TeamTools (5)
                "get_team", "get_team_matches", "get_teams", "get_team_players", "get_team_heroes",
                // ProTools (2)
                "get_pro_players", "get_top_players",
                // MiscTools (4)
                "get_records", "get_parsed_matches", "get_metadata", "get_health");

        // The write tools are opt-in; with the flag unset they must NOT be registered.
        assertThat(names).doesNotContain("request_parse", "refresh_player", "get_parse_request");

        // Exhaustiveness: exactly the 47 read tools above, no more (a dropped @Tool) and no fewer (a new
        // one not added to this test). If this count changes, update the grouped list above to match.
        assertThat(names).hasSize(47);
    }

    @Test
    void jsonSchemaValidatorOverrideIsOnTheClasspath() {
        // Guards the deliberate json-schema-validator 2.0.0 pin in pom.xml: the MCP
        // SDK needs com.networknt.schema.dialect.Dialects, which version 1.5.9 lacks.
        assertThatCode(() -> Class.forName("com.networknt.schema.dialect.Dialects"))
                .doesNotThrowAnyException();
    }
}
