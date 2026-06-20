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
 *
 * <p>The aggregation endpoints (wl/heroes/peers/totals) and the match history all accept the same
 * upstream filter set, so they share one {@link #appendFilters} URL builder. The array-valued
 * filters ({@code included/excluded_account_id}, {@code with/against_hero_id}) are passed as
 * comma-separated strings and expanded into repeated query keys, the shape OpenDota expects.
 */
@Component
public class PlayerTools {

    // Shared @ToolParam descriptions for the canonical filter set. Annotation values must be
    // compile-time constants, so these are referenced (not inlined) to keep the five signatures DRY.
    private static final String D_LIMIT_AGG =
            "Only consider the player's most recent N matches in the aggregation (does NOT cap the rows returned).";
    private static final String D_DATE = "Only matches within the last N days (an integer day count, not a date).";
    private static final String D_WIN = "0 or 1 — restrict to matches the player lost or won.";
    private static final String D_IS_RADIANT = "0 or 1 — player was on Dire or Radiant.";
    private static final String D_INCLUDED = "Comma-separated Steam32 account_ids that must be in the match.";
    private static final String D_EXCLUDED = "Comma-separated Steam32 account_ids that must NOT be in the match.";
    private static final String D_WITH_HERO = "Comma-separated hero_ids that must be on the player's team.";
    private static final String D_AGAINST_HERO = "Comma-separated hero_ids that must be on the enemy team.";
    private static final String D_SIGNIFICANT =
            "0 to include non-standard modes (turbo, etc.); default 1 restricts to standard matchmaking.";
    private static final String D_HAVING = "Minimum number of games for a row to be included.";

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
            return ToolResults.internalError(path, e);
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
            return ToolResults.internalError(path, e);
        }
    }

    @Tool(name = "get_player_wl",
            description = "Win/loss counts for a Steam32 account_id, with the full optional filter set.")
    public String getPlayerWl(long account_id,
                              @ToolParam(required = false, description = D_LIMIT_AGG) Integer limit,
                              @ToolParam(required = false) Integer offset,
                              @ToolParam(required = false, description = D_WIN) Integer win,
                              @ToolParam(required = false) Integer patch,
                              @ToolParam(required = false) Integer game_mode,
                              @ToolParam(required = false) Integer lobby_type,
                              @ToolParam(required = false) Integer region,
                              @ToolParam(required = false, description = D_DATE) Integer date,
                              @ToolParam(required = false) Integer lane_role,
                              @ToolParam(required = false) Integer hero_id,
                              @ToolParam(required = false, description = D_IS_RADIANT) Integer is_radiant,
                              @ToolParam(required = false, description = D_INCLUDED) String included_account_id,
                              @ToolParam(required = false, description = D_EXCLUDED) String excluded_account_id,
                              @ToolParam(required = false, description = D_WITH_HERO) String with_hero_id,
                              @ToolParam(required = false, description = D_AGAINST_HERO) String against_hero_id,
                              @ToolParam(required = false, description = D_SIGNIFICANT) Integer significant,
                              @ToolParam(required = false, description = D_HAVING) Integer having,
                              @ToolParam(required = false) String sort) {
        StringBuilder sb = new StringBuilder("/players/").append(account_id).append("/wl");
        boolean[] started = {false};
        appendFilters(sb, started, limit, offset, win, patch, game_mode, lobby_type, region, date,
                lane_role, hero_id, is_radiant, included_account_id, excluded_account_id, with_hero_id,
                against_hero_id, significant, having, sort);
        return get(sb.toString());
    }

    @Tool(name = "get_player_recent_matches",
            description = "Most recent ~20 matches for a Steam32 account_id. No filters upstream.")
    public String getPlayerRecentMatches(long account_id) {
        return get("/players/" + account_id + "/recentMatches");
    }

    @Tool(name = "get_player_peers",
            description = "Players a Steam32 account_id has played WITH most often (teammates), with win rates. "
                    + "Accepts the full optional filter set.")
    public String getPlayerPeers(long account_id,
                                 @ToolParam(required = false, description = D_LIMIT_AGG) Integer limit,
                                 @ToolParam(required = false) Integer offset,
                                 @ToolParam(required = false, description = D_WIN) Integer win,
                                 @ToolParam(required = false) Integer patch,
                                 @ToolParam(required = false) Integer game_mode,
                                 @ToolParam(required = false) Integer lobby_type,
                                 @ToolParam(required = false) Integer region,
                                 @ToolParam(required = false, description = D_DATE) Integer date,
                                 @ToolParam(required = false) Integer lane_role,
                                 @ToolParam(required = false) Integer hero_id,
                                 @ToolParam(required = false, description = D_IS_RADIANT) Integer is_radiant,
                                 @ToolParam(required = false, description = D_INCLUDED) String included_account_id,
                                 @ToolParam(required = false, description = D_EXCLUDED) String excluded_account_id,
                                 @ToolParam(required = false, description = D_WITH_HERO) String with_hero_id,
                                 @ToolParam(required = false, description = D_AGAINST_HERO) String against_hero_id,
                                 @ToolParam(required = false, description = D_SIGNIFICANT) Integer significant,
                                 @ToolParam(required = false, description = D_HAVING) Integer having,
                                 @ToolParam(required = false) String sort) {
        StringBuilder sb = new StringBuilder("/players/").append(account_id).append("/peers");
        boolean[] started = {false};
        appendFilters(sb, started, limit, offset, win, patch, game_mode, lobby_type, region, date,
                lane_role, hero_id, is_radiant, included_account_id, excluded_account_id, with_hero_id,
                against_hero_id, significant, having, sort);
        return get(sb.toString());
    }

    @Tool(name = "get_player_totals",
            description = "Career totals/averages (kills, GPM, XPM, etc.) aggregated across a Steam32 account_id's "
                    + "matches. Accepts the full optional filter set.")
    public String getPlayerTotals(long account_id,
                                  @ToolParam(required = false, description = D_LIMIT_AGG) Integer limit,
                                  @ToolParam(required = false) Integer offset,
                                  @ToolParam(required = false, description = D_WIN) Integer win,
                                  @ToolParam(required = false) Integer patch,
                                  @ToolParam(required = false) Integer game_mode,
                                  @ToolParam(required = false) Integer lobby_type,
                                  @ToolParam(required = false) Integer region,
                                  @ToolParam(required = false, description = D_DATE) Integer date,
                                  @ToolParam(required = false) Integer lane_role,
                                  @ToolParam(required = false) Integer hero_id,
                                  @ToolParam(required = false, description = D_IS_RADIANT) Integer is_radiant,
                                  @ToolParam(required = false, description = D_INCLUDED) String included_account_id,
                                  @ToolParam(required = false, description = D_EXCLUDED) String excluded_account_id,
                                  @ToolParam(required = false, description = D_WITH_HERO) String with_hero_id,
                                  @ToolParam(required = false, description = D_AGAINST_HERO) String against_hero_id,
                                  @ToolParam(required = false, description = D_SIGNIFICANT) Integer significant,
                                  @ToolParam(required = false, description = D_HAVING) Integer having,
                                  @ToolParam(required = false) String sort) {
        StringBuilder sb = new StringBuilder("/players/").append(account_id).append("/totals");
        boolean[] started = {false};
        appendFilters(sb, started, limit, offset, win, patch, game_mode, lobby_type, region, date,
                lane_role, hero_id, is_radiant, included_account_id, excluded_account_id, with_hero_id,
                against_hero_id, significant, having, sort);
        return get(sb.toString());
    }

    @Tool(name = "get_player_matches",
            description = "Match history for a Steam32 account_id, with the full optional filter set and paging. "
                    + "'project' is a comma-separated list of extra OpenDota match fields to request "
                    + "(e.g. \"kills,deaths,assists,hero_id\"), added on top of the default fields.")
    public String getPlayerMatches(long account_id,
                                   @ToolParam(required = false, description = "Number of matches to return.")
                                   Integer limit,
                                   @ToolParam(required = false) Integer offset,
                                   @ToolParam(required = false, description = D_WIN) Integer win,
                                   @ToolParam(required = false) Integer patch,
                                   @ToolParam(required = false) Integer game_mode,
                                   @ToolParam(required = false) Integer lobby_type,
                                   @ToolParam(required = false) Integer region,
                                   @ToolParam(required = false, description = D_DATE) Integer date,
                                   @ToolParam(required = false) Integer lane_role,
                                   @ToolParam(required = false) Integer hero_id,
                                   @ToolParam(required = false, description = D_IS_RADIANT) Integer is_radiant,
                                   @ToolParam(required = false, description = D_INCLUDED) String included_account_id,
                                   @ToolParam(required = false, description = D_EXCLUDED) String excluded_account_id,
                                   @ToolParam(required = false, description = D_WITH_HERO) String with_hero_id,
                                   @ToolParam(required = false, description = D_AGAINST_HERO) String against_hero_id,
                                   @ToolParam(required = false, description = D_SIGNIFICANT) Integer significant,
                                   @ToolParam(required = false, description = D_HAVING) Integer having,
                                   @ToolParam(required = false) String sort,
                                   @ToolParam(required = false,
                                           description = "Comma-separated extra match fields to include "
                                                   + "(e.g. \"kills,deaths,assists,hero_id\").")
                                   String project) {
        StringBuilder sb = new StringBuilder("/players/").append(account_id).append("/matches");
        boolean[] started = {false};
        appendFilters(sb, started, limit, offset, win, patch, game_mode, lobby_type, region, date,
                lane_role, hero_id, is_radiant, included_account_id, excluded_account_id, with_hero_id,
                against_hero_id, significant, having, sort);
        // `project` is matches-only: a repeatable query key expanded one project=<field> per token.
        Query.appendRepeated(sb, started, "project", project);
        return get(sb.toString());
    }

    @Tool(name = "get_player_heroes",
            description = "Per-hero stats (games, win rate, etc.) for a Steam32 account_id, aggregated "
                    + "across the player's matches. Returns a row for EVERY hero, not a top-N list. "
                    + "Accepts the full optional filter set.")
    public String getPlayerHeroes(long account_id,
                                  @ToolParam(required = false,
                                          description = "Only aggregate over the player's most recent N matches "
                                                  + "(it caps the matches considered, NOT the number of heroes "
                                                  + "returned — the response still covers all heroes).")
                                  Integer limit,
                                  @ToolParam(required = false) Integer offset,
                                  @ToolParam(required = false, description = D_WIN) Integer win,
                                  @ToolParam(required = false) Integer patch,
                                  @ToolParam(required = false) Integer game_mode,
                                  @ToolParam(required = false) Integer lobby_type,
                                  @ToolParam(required = false) Integer region,
                                  @ToolParam(required = false, description = D_DATE) Integer date,
                                  @ToolParam(required = false) Integer lane_role,
                                  @ToolParam(required = false) Integer hero_id,
                                  @ToolParam(required = false, description = D_IS_RADIANT) Integer is_radiant,
                                  @ToolParam(required = false, description = D_INCLUDED) String included_account_id,
                                  @ToolParam(required = false, description = D_EXCLUDED) String excluded_account_id,
                                  @ToolParam(required = false, description = D_WITH_HERO) String with_hero_id,
                                  @ToolParam(required = false, description = D_AGAINST_HERO) String against_hero_id,
                                  @ToolParam(required = false, description = D_SIGNIFICANT) Integer significant,
                                  @ToolParam(required = false, description = D_HAVING) Integer having,
                                  @ToolParam(required = false) String sort) {
        StringBuilder sb = new StringBuilder("/players/").append(account_id).append("/heroes");
        boolean[] started = {false};
        appendFilters(sb, started, limit, offset, win, patch, game_mode, lobby_type, region, date,
                lane_role, hero_id, is_radiant, included_account_id, excluded_account_id, with_hero_id,
                against_hero_id, significant, having, sort);
        return get(sb.toString());
    }

    @Tool(name = "get_player_ratings",
            description = "MMR rating history (rating over time) for a Steam32 account_id.")
    public String getPlayerRatings(long account_id) {
        return get("/players/" + account_id + "/ratings");
    }

    @Tool(name = "get_player_rankings",
            description = "Per-hero ranking percentiles for a Steam32 account_id (how the player ranks on each hero).")
    public String getPlayerRankings(long account_id) {
        return get("/players/" + account_id + "/rankings");
    }

    @Tool(name = "get_player_counts",
            description = "Counts of a Steam32 account_id's matches grouped by category "
                    + "(game mode, lobby type, lane role, region, patch, etc.).")
    public String getPlayerCounts(long account_id) {
        return get("/players/" + account_id + "/counts");
    }

    @Tool(name = "get_player_histograms",
            description = "Distribution (histogram) of a single match stat for a Steam32 account_id. The field is a "
                    + "match stat name, e.g. kills, deaths, assists, gold_per_min, xp_per_min, last_hits, duration.")
    public String getPlayerHistograms(long account_id, String field) {
        if (field == null || field.isBlank()) {
            return ToolResults.badArg("/players/histograms", "field is required");
        }
        return get("/players/" + account_id + "/histograms/" + OpenDotaClient.encode(field));
    }

    @Tool(name = "get_player_pros",
            description = "Professional players a Steam32 account_id has played with or against.")
    public String getPlayerPros(long account_id) {
        return get("/players/" + account_id + "/pros");
    }

    @Tool(name = "get_player_wardmap",
            description = "Observer/sentry ward placement heatmap data for a Steam32 account_id.")
    public String getPlayerWardmap(long account_id) {
        return get("/players/" + account_id + "/wardmap");
    }

    @Tool(name = "get_player_wordcloud",
            description = "Frequencies of chat words used by a Steam32 account_id (a word-cloud data source).")
    public String getPlayerWordcloud(long account_id) {
        return get("/players/" + account_id + "/wordcloud");
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

    /**
     * Append the canonical OpenDota player filter set to {@code sb} via {@link Query}. Scalars are
     * skipped when null; the array-valued filters are comma-separated strings expanded into repeated
     * query keys. All endpoints that share this set accept every key, so it is applied uniformly.
     */
    private static void appendFilters(StringBuilder sb, boolean[] started,
                                      Integer limit, Integer offset, Integer win, Integer patch,
                                      Integer game_mode, Integer lobby_type, Integer region, Integer date,
                                      Integer lane_role, Integer hero_id, Integer is_radiant,
                                      String included_account_id, String excluded_account_id,
                                      String with_hero_id, String against_hero_id,
                                      Integer significant, Integer having, String sort) {
        Query.append(sb, started, "limit", limit);
        Query.append(sb, started, "offset", offset);
        Query.append(sb, started, "win", win);
        Query.append(sb, started, "patch", patch);
        Query.append(sb, started, "game_mode", game_mode);
        Query.append(sb, started, "lobby_type", lobby_type);
        Query.append(sb, started, "region", region);
        Query.append(sb, started, "date", date);
        Query.append(sb, started, "lane_role", lane_role);
        Query.append(sb, started, "hero_id", hero_id);
        Query.append(sb, started, "is_radiant", is_radiant);
        Query.appendRepeated(sb, started, "included_account_id", included_account_id);
        Query.appendRepeated(sb, started, "excluded_account_id", excluded_account_id);
        Query.appendRepeated(sb, started, "with_hero_id", with_hero_id);
        Query.appendRepeated(sb, started, "against_hero_id", against_hero_id);
        Query.append(sb, started, "significant", significant);
        Query.append(sb, started, "having", having);
        Query.append(sb, started, "sort", sort);
    }
}
