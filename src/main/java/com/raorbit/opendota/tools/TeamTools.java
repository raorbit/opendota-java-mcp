package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposing OpenDota professional-team data.
 *
 * <p>Every tool returns a {@code String}: raw 2xx JSON passthrough or a JSON error envelope.
 * Tools never throw.
 */
@Component
public class TeamTools {

    private final OpenDotaClient client;

    public TeamTools(OpenDotaClient client) {
        this.client = client;
    }

    @Tool(name = "get_team",
            description = "OpenDota professional team profile (name, tag, rating, win/loss) for a team_id.")
    public String getTeam(long team_id) {
        return get("/teams/" + team_id);
    }

    @Tool(name = "get_team_matches",
            description = "Recent matches played by a professional team_id (opponent, league, result).")
    public String getTeamMatches(long team_id) {
        return get("/teams/" + team_id + "/matches");
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
