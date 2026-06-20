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

        assertThat(names).contains(
                "search_players", "get_player", "get_player_wl", "get_player_recent_matches",
                "get_player_matches", "get_player_heroes",
                "get_match", "get_pro_matches", "get_public_matches", "get_live_games",
                "list_heroes", "get_hero_stats", "get_hero_rankings", "get_constants",
                "run_sql_explorer", "get_sql_schema",
                "get_hero_matchups", "get_hero_item_popularity", "get_hero_durations",
                "get_hero_players", "get_hero_matches",
                "get_team", "get_team_matches", "get_league", "get_league_matches", "get_pro_players");
    }

    @Test
    void jsonSchemaValidatorOverrideIsOnTheClasspath() {
        // Guards the deliberate json-schema-validator 2.0.0 pin in pom.xml: the MCP
        // SDK needs com.networknt.schema.dialect.Dialects, which version 1.5.9 lacks.
        assertThatCode(() -> Class.forName("com.networknt.schema.dialect.Dialects"))
                .doesNotThrowAnyException();
    }
}
