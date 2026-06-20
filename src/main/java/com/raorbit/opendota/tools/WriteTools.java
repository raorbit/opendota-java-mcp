package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The OpenDota MCP server is read-only by default. These are its <em>only</em> write tools — they
 * POST to OpenDota to queue work (a match parse, a player-history refresh) — and they are
 * <strong>off unless explicitly enabled</strong> via {@code opendota.write-tools-enabled=true}. When
 * the flag is unset the bean is never created, so the tools do not appear in {@code tools/list} at all.
 *
 * <p>Every tool returns a {@code String}: raw 2xx JSON passthrough or a JSON error envelope; tools
 * never throw. Writes go through {@link OpenDotaClient#postJson} (rate-limited, never cached).
 */
@Component
@ConditionalOnProperty(name = "opendota.write-tools-enabled", havingValue = "true")
public class WriteTools {

    private final OpenDotaClient client;

    public WriteTools(OpenDotaClient client) {
        this.client = client;
    }

    @Tool(name = "request_parse",
            description = "Queue a full parse of a match_id (download + parse the replay) so its advanced "
                    + "post-game fields become available. Returns a parse job; poll it with get_parse_request. "
                    + "This is a WRITE action and costs ~10x a normal API call.")
    public String requestParse(long match_id) {
        return post("/request/" + match_id);
    }

    @Tool(name = "refresh_player",
            description = "Trigger OpenDota to refresh a Steam32 account_id's recent match history from Steam. "
                    + "This is a WRITE action; the refreshed data appears on subsequent reads.")
    public String refreshPlayer(long account_id) {
        return post("/players/" + account_id + "/refresh");
    }

    @Tool(name = "get_parse_request",
            description = "Check the status of a parse job by its job_id (the id returned by request_parse).")
    public String getParseRequest(long job_id) {
        String path = "/request/" + job_id;
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }

    /** POST {@code path}, passing the raw body through or mapping a failure to the error envelope. */
    private String post(String path) {
        try {
            return client.postJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path);
        }
    }
}
