package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposing OpenDota professional-scene player data.
 *
 * <p>Every tool returns a {@code String}: raw 2xx JSON passthrough or a JSON error envelope.
 * Tools never throw.
 */
@Component
public class ProTools {

    private final OpenDotaClient client;

    public ProTools(OpenDotaClient client) {
        this.client = client;
    }

    @Tool(name = "get_pro_players",
            description = "List of known professional players (account_id, name, team affiliation). "
                    + "Useful for resolving a pro's name to an account_id without the flaky /search.")
    public String getProPlayers() {
        return get("/proPlayers");
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
