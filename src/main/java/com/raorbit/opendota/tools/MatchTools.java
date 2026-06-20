package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for OpenDota match endpoints.
 *
 * <p>Each tool builds an API path and returns the raw 2xx JSON body verbatim,
 * or a JSON error envelope (never throws) when the upstream call fails.
 */
@Component
public class MatchTools {

    private final OpenDotaClient client;

    public MatchTools(OpenDotaClient client) {
        this.client = client;
    }

    @Tool(name = "get_match",
            description = "Full detail for a single match_id. Advanced fields are null for unparsed matches.")
    public String getMatch(long match_id) {
        String path = "/matches/" + match_id;
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path, e);
        }
    }

    @Tool(name = "get_pro_matches",
            description = "Recent professional matches; paginate with less_than_match_id.")
    public String getProMatches(@ToolParam(required = false) Long less_than_match_id) {
        QueryBuilder qb = new QueryBuilder("/proMatches");
        qb.append("less_than_match_id", less_than_match_id);
        String path = qb.build();
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path, e);
        }
    }

    @Tool(name = "get_public_matches",
            description = "Recent public matches; optional rank window (10-15 Herald, 20-25 Guardian, "
                    + "30-35 Crusader, 40-45 Archon, 50-55 Legend, 60-65 Ancient, 70-75 Divine, 80 Immortal) "
                    + "and pagination.")
    public String getPublicMatches(@ToolParam(required = false) Long less_than_match_id,
                                   @ToolParam(required = false) Integer min_rank,
                                   @ToolParam(required = false) Integer max_rank) {
        QueryBuilder qb = new QueryBuilder("/publicMatches");
        qb.append("less_than_match_id", less_than_match_id);
        qb.append("min_rank", min_rank);
        qb.append("max_rank", max_rank);
        String path = qb.build();
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path, e);
        }
    }

    @Tool(name = "get_live_games",
            description = "Currently live games tracked by OpenDota.")
    public String getLiveGames() {
        String path = "/live";
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path, e);
        }
    }

    /**
     * Accumulates query parameters onto a base path, appending each only when
     * its value is non-null. The first appended param uses {@code '?'} and the
     * rest use {@code '&'}. Values are URL-encoded so the helper can never become
     * a query-injection sink: today's callers pass only numeric values (left
     * byte-for-byte unchanged by the encoder), but a future non-numeric parameter
     * is then automatically safe.
     *
     * <p>Package-private (not {@code private}) so its encoding can be unit-tested directly.
     */
    static final class QueryBuilder {

        private final StringBuilder sb;
        private boolean first = true;

        QueryBuilder(String basePath) {
            this.sb = new StringBuilder(basePath);
        }

        void append(String name, Object value) {
            if (value == null) {
                return;
            }
            sb.append(first ? '?' : '&').append(name).append('=')
                    .append(OpenDotaClient.encode(String.valueOf(value)));
            first = false;
        }

        String build() {
            return sb.toString();
        }
    }
}
