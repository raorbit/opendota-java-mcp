package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposing OpenDota hero data and static game constants.
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
        String path = "/heroes";
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_hero_stats", description = "Aggregate hero pick/win/ban stats by rank tier from recent matches.")
    public String getHeroStats() {
        String path = "/heroStats";
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_hero_rankings", description = "Top-ranked players for a given hero_id.")
    public String getHeroRankings(String hero_id) {
        if (hero_id == null || hero_id.isBlank()) {
            return ToolResults.badArg("/rankings", "hero_id is required");
        }
        String path = "/rankings?hero_id=" + OpenDotaClient.encode(hero_id);
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_benchmarks",
            description = "Percentile benchmarks (GPM, XPM, kills, last hits, etc.) for a given hero_id over recent matches.")
    public String getBenchmarks(String hero_id) {
        if (hero_id == null || hero_id.isBlank()) {
            return ToolResults.badArg("/benchmarks", "hero_id is required");
        }
        String path = "/benchmarks?hero_id=" + OpenDotaClient.encode(hero_id);
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_distributions",
            description = "Distributions of MMR/rank and country data across the player base.")
    public String getDistributions() {
        String path = "/distributions";
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    @Tool(name = "get_constants", description = "Static game constants for a named resource (e.g. heroes, items, abilities, ability_ids, item_ids, game_mode, lobby_type, patch, region, hero_abilities, hero_lore, neutral_abilities, aghs_desc, chat_wheel, countries, cluster, order_types, permanent_buffs, player_colors, skillshots, xp_level).")
    public String getConstants(String resource) {
        if (resource == null || resource.isBlank()) {
            return ToolResults.badArg("/constants", "resource is required");
        }
        String path = "/constants/" + OpenDotaClient.encode(resource);
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }
}
