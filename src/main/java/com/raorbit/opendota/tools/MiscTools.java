package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for OpenDota's miscellaneous / site-wide endpoints (records, the parsed-match feed,
 * and service metadata/health).
 *
 * <p>Every tool returns a {@code String}: raw 2xx JSON passthrough or a JSON error envelope.
 * Tools never throw.
 */
@Component
public class MiscTools {

    private final OpenDotaClient client;

    public MiscTools(OpenDotaClient client) {
        this.client = client;
    }

    @Tool(name = "get_records",
            description = "All-time record holders for a single match field (the leaderboard for that stat). "
                    + "The field is a match-level stat name, e.g. kills, deaths, duration, gold, xp, last_hits.")
    public String getRecords(String field) {
        if (field == null || field.isBlank()) {
            return ToolResults.badArg("/records", "field is required");
        }
        return get("/records/" + OpenDotaClient.encode(field));
    }

    @Tool(name = "get_parsed_matches",
            description = "Recently parsed match IDs (the public parse feed), most recent first. Paginate by "
                    + "passing the smallest match_id from the previous page as less_than_match_id.")
    public String getParsedMatches(@ToolParam(required = false) Long less_than_match_id) {
        String path = "/parsedMatches";
        if (less_than_match_id != null) {
            path += "?less_than_match_id=" + less_than_match_id;
        }
        return get(path);
    }

    @Tool(name = "get_metadata",
            description = "OpenDota site-wide metadata (current patch, banners, and aggregate counts).")
    public String getMetadata() {
        return get("/metadata");
    }

    @Tool(name = "get_health",
            description = "OpenDota API service health/status snapshot.")
    public String getHealth() {
        return get("/health");
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
