package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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

    @Tool(name = "get_teams",
            description = "List of professional teams ranked by rating (paginated). Pass page (0-based) for "
                    + "lower-ranked teams.")
    public String getTeams(@ToolParam(required = false) Integer page) {
        StringBuilder sb = new StringBuilder("/teams");
        boolean[] started = {false};
        Query.append(sb, started, "page", page);
        return get(sb.toString());
    }

    @Tool(name = "get_team_players",
            description = "Players who have played for a professional team_id, with games and win rate.")
    public String getTeamPlayers(long team_id) {
        return get("/teams/" + team_id + "/players");
    }

    @Tool(name = "get_team_heroes",
            description = "Heroes a professional team_id has drafted, with games and win rate.")
    public String getTeamHeroes(long team_id) {
        return get("/teams/" + team_id + "/heroes");
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
