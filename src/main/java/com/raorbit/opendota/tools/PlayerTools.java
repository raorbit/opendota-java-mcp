package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposing OpenDota player endpoints.
 *
 * <p>Every tool returns a {@code String}: raw 2xx JSON passes through verbatim,
 * and any {@link OpenDotaException} is converted into a JSON error envelope via
 * {@link ToolResults#fromException(OpenDotaException)}. Tools never throw.
 */
@Component
public class PlayerTools {

    private final OpenDotaClient client;

    public PlayerTools(OpenDotaClient client) {
        this.client = client;
    }

    @Tool(name = "search_players",
            description = "Search OpenDota for Dota 2 players by name; returns candidate account_ids.")
    public String searchPlayers(String q) {
        if (q == null || q.isBlank()) {
            return ToolResults.badArg("/search", "q is required");
        }
        String path = "/search?q=" + OpenDotaClient.encode(q);
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_player",
            description = "OpenDota player profile, rank tier and estimated MMR for a Steam32 account_id.")
    public String getPlayer(long account_id) {
        String path = "/players/" + account_id;
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_player_wl",
            description = "Win/loss counts for a Steam32 account_id, with optional filters.")
    public String getPlayerWl(long account_id,
                              @ToolParam(required = false) Integer limit,
                              @ToolParam(required = false) Integer offset,
                              @ToolParam(required = false) Integer hero_id,
                              @ToolParam(required = false) Integer win,
                              @ToolParam(required = false) Integer game_mode,
                              @ToolParam(required = false) Integer lobby_type,
                              @ToolParam(required = false) Integer date) {
        StringBuilder sb = new StringBuilder("/players/").append(account_id).append("/wl");
        boolean[] started = {false};
        appendParam(sb, started, "limit", limit);
        appendParam(sb, started, "offset", offset);
        appendParam(sb, started, "hero_id", hero_id);
        appendParam(sb, started, "win", win);
        appendParam(sb, started, "game_mode", game_mode);
        appendParam(sb, started, "lobby_type", lobby_type);
        appendParam(sb, started, "date", date);
        String path = sb.toString();
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_player_recent_matches",
            description = "Most recent ~20 matches for a Steam32 account_id. No filters upstream.")
    public String getPlayerRecentMatches(long account_id) {
        String path = "/players/" + account_id + "/recentMatches";
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_player_peers",
            description = "Players a Steam32 account_id has played WITH most often (teammates), with win rates.")
    public String getPlayerPeers(long account_id) {
        String path = "/players/" + account_id + "/peers";
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_player_totals",
            description = "Career totals/averages (kills, GPM, XPM, etc.) aggregated across a Steam32 account_id's matches.")
    public String getPlayerTotals(long account_id) {
        String path = "/players/" + account_id + "/totals";
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_player_matches",
            description = "Match history for a Steam32 account_id, with optional filters/paging. "
                    + "'project' is a comma-separated list of extra OpenDota match fields to request "
                    + "(e.g. \"kills,deaths,assists,hero_id\"), added on top of the default fields.")
    public String getPlayerMatches(long account_id,
                                   @ToolParam(required = false) Integer limit,
                                   @ToolParam(required = false) Integer offset,
                                   @ToolParam(required = false) Integer hero_id,
                                   @ToolParam(required = false) Integer win,
                                   @ToolParam(required = false) Integer game_mode,
                                   @ToolParam(required = false) Integer lobby_type,
                                   @ToolParam(required = false) Integer date,
                                   @ToolParam(required = false) String sort,
                                   @ToolParam(required = false) String project,
                                   @ToolParam(required = false) Integer with_hero_id,
                                   @ToolParam(required = false) Integer against_hero_id,
                                   @ToolParam(required = false) Integer included_account_id,
                                   @ToolParam(required = false) Integer lane_role,
                                   @ToolParam(required = false) Integer patch,
                                   @ToolParam(required = false) Integer is_radiant) {
        StringBuilder sb = new StringBuilder("/players/").append(account_id).append("/matches");
        boolean[] started = {false};
        appendParam(sb, started, "limit", limit);
        appendParam(sb, started, "offset", offset);
        appendParam(sb, started, "hero_id", hero_id);
        appendParam(sb, started, "win", win);
        appendParam(sb, started, "game_mode", game_mode);
        appendParam(sb, started, "lobby_type", lobby_type);
        appendParam(sb, started, "date", date);
        appendParam(sb, started, "sort", sort);
        appendParam(sb, started, "with_hero_id", with_hero_id);
        appendParam(sb, started, "against_hero_id", against_hero_id);
        appendParam(sb, started, "included_account_id", included_account_id);
        appendParam(sb, started, "lane_role", lane_role);
        appendParam(sb, started, "patch", patch);
        appendParam(sb, started, "is_radiant", is_radiant);
        // OpenDota's `project` is a repeatable query key; expand the comma-separated list
        // into one project=<field> per token so the upstream trims to exactly those fields.
        appendRepeated(sb, started, "project", project);
        String path = sb.toString();
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_player_heroes",
            description = "Per-hero stats for a Steam32 account_id, with optional filters.")
    public String getPlayerHeroes(long account_id,
                                  @ToolParam(required = false) Integer limit,
                                  @ToolParam(required = false) Integer date) {
        StringBuilder sb = new StringBuilder("/players/").append(account_id).append("/heroes");
        boolean[] started = {false};
        appendParam(sb, started, "limit", limit);
        appendParam(sb, started, "date", date);
        String path = sb.toString();
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    /**
     * Append {@code name=value} to the query string only when {@code value != null},
     * using {@code '?'} for the first parameter and {@code '&'} thereafter. The value
     * is URL-encoded via {@link OpenDotaClient#encode(String)}.
     */
    private static void appendParam(StringBuilder sb, boolean[] started, String name, Object value) {
        if (value == null) {
            return;
        }
        sb.append(started[0] ? '&' : '?');
        started[0] = true;
        sb.append(name).append('=').append(OpenDotaClient.encode(String.valueOf(value)));
    }

    /**
     * Append a repeatable query parameter once per non-blank comma-separated token in
     * {@code csv} (e.g. {@code "kills,deaths"} → {@code project=kills&project=deaths}),
     * which is how OpenDota expects array-valued query params. A {@code null} csv is skipped.
     */
    private static void appendRepeated(StringBuilder sb, boolean[] started, String name, String csv) {
        if (csv == null) {
            return;
        }
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                appendParam(sb, started, name, t);
            }
        }
    }
}
