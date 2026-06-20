package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposing OpenDota hero data, the per-hero deep-dive endpoints, and static game constants.
 *
 * <p>Every tool returns a {@code String}: either the raw 2xx JSON body from the
 * OpenDota API (passthrough) or a JSON error envelope. Tools never throw.
 */
@Component
public class HeroTools {

    private final OpenDotaClient client;

    public HeroTools(OpenDotaClient client) {
        this.client = client;
    }

    @Tool(name = "list_heroes", description = "Static list of all Dota 2 heroes.")
    public String listHeroes() {
        return get("/heroes");
    }

    @Tool(name = "get_hero_stats", description = "Aggregate hero pick/win/ban stats by rank tier from recent matches.")
    public String getHeroStats() {
        return get("/heroStats");
    }

    @Tool(name = "get_hero_rankings", description = "Top-ranked players for a given hero_id.")
    public String getHeroRankings(Integer hero_id) {
        if (hero_id == null) {
            return ToolResults.badArg("/rankings", "hero_id is required");
        }
        return get("/rankings?hero_id=" + hero_id);
    }

    @Tool(name = "get_benchmarks",
            description = "Percentile benchmarks (GPM, XPM, kills, last hits, etc.) for a given hero_id over recent matches.")
    public String getBenchmarks(Integer hero_id) {
        if (hero_id == null) {
            return ToolResults.badArg("/benchmarks", "hero_id is required");
        }
        return get("/benchmarks?hero_id=" + hero_id);
    }

    @Tool(name = "get_hero_matchups",
            description = "How a given hero fares against every other hero (games and wins in that matchup) — "
                    + "the counter-pick / lane-matchup data source. By hero_id.")
    public String getHeroMatchups(Integer hero_id) {
        if (hero_id == null) {
            return ToolResults.badArg("/heroes/matchups", "hero_id is required");
        }
        return get("/heroes/" + hero_id + "/matchups");
    }

    @Tool(name = "get_hero_item_popularity",
            description = "Item purchase popularity for a given hero by game phase (start/early/mid/late game), "
                    + "from recent professional matches. By hero_id.")
    public String getHeroItemPopularity(Integer hero_id) {
        if (hero_id == null) {
            return ToolResults.badArg("/heroes/itemPopularity", "hero_id is required");
        }
        return get("/heroes/" + hero_id + "/itemPopularity");
    }

    @Tool(name = "get_hero_durations",
            description = "A given hero's win rate and game count bucketed by match duration. By hero_id.")
    public String getHeroDurations(Integer hero_id) {
        if (hero_id == null) {
            return ToolResults.badArg("/heroes/durations", "hero_id is required");
        }
        return get("/heroes/" + hero_id + "/durations");
    }

    @Tool(name = "get_hero_players",
            description = "Players who have played a given hero, with games and win rate. By hero_id.")
    public String getHeroPlayers(Integer hero_id) {
        if (hero_id == null) {
            return ToolResults.badArg("/heroes/players", "hero_id is required");
        }
        return get("/heroes/" + hero_id + "/players");
    }

    @Tool(name = "get_hero_matches",
            description = "Recent high-level (professional) matches featuring a given hero. By hero_id.")
    public String getHeroMatches(Integer hero_id) {
        if (hero_id == null) {
            return ToolResults.badArg("/heroes/matches", "hero_id is required");
        }
        return get("/heroes/" + hero_id + "/matches");
    }

    @Tool(name = "get_distributions",
            description = "Distributions of MMR/rank and country data across the player base.")
    public String getDistributions() {
        return get("/distributions");
    }

    @Tool(name = "get_constants", description = "Static game constants for a named resource (e.g. heroes, items, abilities, ability_ids, item_ids, game_mode, lobby_type, patch, region, hero_abilities, hero_lore, neutral_abilities, aghs_desc, chat_wheel, countries, cluster, order_types, permanent_buffs, player_colors, skillshots, xp_level).")
    public String getConstants(String resource) {
        if (resource == null || resource.isBlank()) {
            return ToolResults.badArg("/constants", "resource is required");
        }
        return get("/constants/" + OpenDotaClient.encode(resource));
    }

    /** GET {@code path}, passing the raw body through or mapping a failure to the error envelope. */
    private String get(String path) {
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }
}
