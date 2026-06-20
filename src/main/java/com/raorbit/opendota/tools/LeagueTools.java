package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposing OpenDota league / tournament data.
 *
 * <p>Every tool returns a {@code String}: raw 2xx JSON passthrough or a JSON error envelope.
 * Tools never throw.
 */
@Component
public class LeagueTools {

    private final OpenDotaClient client;

    public LeagueTools(OpenDotaClient client) {
        this.client = client;
    }

    @Tool(name = "get_league",
            description = "OpenDota league/tournament info (name, tier) for a league_id.")
    public String getLeague(long league_id) {
        return get("/leagues/" + league_id);
    }

    @Tool(name = "get_league_matches",
            description = "Matches played in a given league_id (tournament).")
    public String getLeagueMatches(long league_id) {
        return get("/leagues/" + league_id + "/matches");
    }

    /** GET {@code path}, passing the raw body through or mapping a failure to the error envelope. */
    private String get(String path) {
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path, e);
        }
    }
}
