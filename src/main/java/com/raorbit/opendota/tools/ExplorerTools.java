package com.raorbit.opendota.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposing OpenDota's ad-hoc SQL warehouse: a guarded read-only {@code /explorer}
 * query tool plus a {@code /schema} companion so the model can ground its queries.
 *
 * <p>Both return a {@code String} and never throw. {@code get_sql_schema} is a raw passthrough like
 * every other tool, but {@code run_sql_explorer} is the one tool that <em>reshapes</em> its body:
 * OpenDota's {@code /explorer} hands back node-postgres's internal result object (a ~600-token
 * {@code _types} OID map, {@code _parsers}, {@code RowCtor}, per-column field metadata, …), almost all
 * of which is context waste, so it is slimmed to {@code {command, rowCount, fields:[names], rows,
 * sql_executed}}. The SQL guardrails are defense-in-depth plus predictable UX (clear errors, a bounded
 * result size) layered on top of OpenDota's already read-only DB role — not the only thing standing
 * between a bad query and the database.
 *
 * <p>The keyword/limit checks are heuristics over the raw SQL text (no parser): they pair the
 * SELECT/WITH-only start rule, the single-statement rule and the whole-word blocklist into a
 * reasonable read-only envelope. A blocked word inside a string literal (e.g. {@code = 'set'}) can
 * be a false positive — the documented tradeoff of regex-over-SQL.
 */
@Component
public class ExplorerTools {

    private static final int DEFAULT_ROW_LIMIT = 200;
    private static final int MAX_ROW_LIMIT = 2000;

    /** Whole-word ({@code \b}) so "offset" isn't caught by "set" nor "into" by "in". */
    private static final Pattern BLOCKED = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|copy|merge|call|do|"
            + "vacuum|analyze|reindex|cluster|comment|set|reset|begin|commit|rollback|savepoint|"
            + "listen|notify|lock|prepare|execute|deallocate|into|pg_sleep|pg_read_file|lo_import|"
            + "lo_export|dblink)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STARTS_READONLY =
            Pattern.compile("^\\s*(select|with)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_LIMIT =
            Pattern.compile("\\blimit\\s+(\\d+)(\\s+offset\\s+\\d+)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_SEMICOLON = Pattern.compile(";\\s*$");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(ExplorerTools.class);

    private final OpenDotaClient client;

    public ExplorerTools(OpenDotaClient client) {
        this.client = client;
    }

    @Tool(name = "run_sql_explorer",
            description = "Run a read-only PostgreSQL query against OpenDota's data warehouse via /explorer. "
                    + "Use for analytical questions the fixed endpoints can't answer: custom aggregations, filters, "
                    + "and joins across matches/players/heroes/teams/leagues. SELECT or WITH only; a row LIMIT is "
                    + "enforced automatically. Call get_sql_schema first if unsure of table or column names. Common "
                    + "tables: matches, player_matches, players, notable_players, teams, team_match, leagues, "
                    + "picks_bans, public_matches, hero_ranking, match_patch.")
    public String runSqlExplorer(
            @ToolParam(description = "A single read-only query (SELECT ... or WITH ...). No trailing semicolon.")
            String sql,
            @ToolParam(required = false, description = "Max rows to return. Default 200, hard cap 2000.")
            Integer row_limit) {
        int cap = Math.min(Math.max(row_limit == null ? DEFAULT_ROW_LIMIT : row_limit, 1), MAX_ROW_LIMIT);
        Guard guard = guardSql(sql, cap);
        if (!guard.ok()) {
            return ToolResults.badArg("/explorer", guard.error());
        }
        String path = "/explorer?sql=" + OpenDotaClient.encode(guard.sql());
        try {
            return shape(client.getJson(path), guard.sql());
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError("/explorer", e);
        }
    }

    @Tool(name = "get_sql_schema",
            description = "List the tables and columns available to run_sql_explorer (table_name, column_name, "
                    + "data_type). Call this before writing a query if unsure of the schema.")
    public String getSqlSchema() {
        String path = "/schema";
        try {
            return client.getJson(path);
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError(path, e);
        }
    }

    // --- SQL guardrails (pure string logic, ported from the TS reference) ---

    private record Guard(boolean ok, String sql, String error) {
        static Guard ok(String sql) {
            return new Guard(true, sql, null);
        }

        static Guard fail(String error) {
            return new Guard(false, null, error);
        }
    }

    /** Validate a read-only single statement and bound its result size to {@code cap} rows. */
    static Guard guardSql(String raw, int cap) {
        if (raw == null || raw.isBlank()) {
            return Guard.fail("sql is required");
        }
        String sql = raw.trim();
        // 1. Comments can hide a second statement or a blocked keyword — reject outright.
        if (sql.contains("--") || sql.contains("/*")) {
            return Guard.fail("SQL comments are not allowed.");
        }
        // 2. Single statement only: strip one trailing ';', reject any that remain.
        sql = TRAILING_SEMICOLON.matcher(sql).replaceFirst("");
        if (sql.contains(";")) {
            return Guard.fail("Only a single statement is allowed.");
        }
        // 3. Read-only: must start with SELECT or WITH.
        if (!STARTS_READONLY.matcher(sql).find()) {
            return Guard.fail("Only SELECT / WITH queries are allowed.");
        }
        // 4. No write / DDL / dangerous functions anywhere in the body.
        if (BLOCKED.matcher(sql).find()) {
            return Guard.fail("Query contains a disallowed keyword.");
        }
        // 5. Bound the result size.
        return Guard.ok(applyLimit(sql, cap));
    }

    /**
     * Ensures the query ends with a LIMIT clause bounded by {@code cap}.
     * If a limit exists and exceeds cap, it is clamped; otherwise, a limit is appended.
     * Preserves trailing OFFSET if present after LIMIT.
     *
     * <p>A LIMIT literal too large to fit in a {@code long} (e.g. {@code LIMIT 9999999999999999999})
     * is, a fortiori, larger than {@code cap}, so it is clamped to {@code cap} like any other
     * oversized LIMIT — rather than letting {@link Long#parseLong} throw a
     * {@link NumberFormatException} out of the (never-throwing) tool handler.
     */
    static String applyLimit(String sql, int cap) {
        Matcher m = TRAILING_LIMIT.matcher(sql);
        if (m.find()) {
            long requested;
            try {
                requested = Long.parseLong(m.group(1));
            } catch (NumberFormatException overflow) {
                requested = cap;
            }
            String offset = m.group(2) != null ? m.group(2) : "";
            return m.replaceFirst("LIMIT " + Math.min(requested, cap) + offset);
        }
        return sql + " LIMIT " + cap;
    }

    /**
     * Slim OpenDota's {@code /explorer} body to the useful fields. The upstream body is
     * node-postgres's raw result object; this keeps {@code command}, {@code rowCount}, the column
     * {@code names}, {@code rows}, and the executed SQL, and drops the rest (the ~600-token
     * {@code _types} map, {@code _parsers}, {@code RowCtor}, {@code _prebuiltEmptyResultObject},
     * {@code oid}, and the verbose per-column field metadata).
     *
     * <p>A SQL error arrives as an HTTP 200 with a non-null top-level {@code "err"} — note a
     * <em>successful</em> response also carries {@code "err":null}, so the check is {@code hasNonNull},
     * not mere presence, and an {@code err} <em>column</em> lives under {@code rows[]} rather than at the
     * top level. The error is surfaced as the standard error envelope; a body that isn't valid JSON is
     * passed through unchanged.
     */
    private static String shape(String body, String sqlExecuted) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            return body;
        }
        if (root.hasNonNull("err")) {
            return ToolResults.fromException(new OpenDotaException(422, "/explorer", root.get("err").asText()));
        }
        JsonNode rows = root.path("rows");
        ObjectNode out = MAPPER.createObjectNode();
        if (root.hasNonNull("command")) {
            out.set("command", root.get("command"));
        }
        out.put("rowCount", root.path("rowCount").asInt(rows.size()));
        ArrayNode names = out.putArray("fields");
        for (JsonNode field : root.path("fields")) {
            if (field.hasNonNull("name")) {
                names.add(field.get("name").asText());
            }
        }
        out.set("rows", rows.isArray() ? rows : MAPPER.createArrayNode());
        out.put("sql_executed", sqlExecuted);
        try {
            return MAPPER.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            // Re-serializing an ObjectNode we just built should never fail; if it somehow does, fall
            // back to the raw body but leave a trace (the first catch above is the documented non-JSON
            // passthrough and is intentionally not logged).
            log.warn("failed to serialize the shaped /explorer result; passing the raw body through", e);
            return body;
        }
    }
}
