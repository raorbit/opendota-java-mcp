package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.raorbit.opendota.client.ToolResults;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposing OpenDota's ad-hoc SQL warehouse: a guarded read-only {@code /explorer}
 * query tool plus a {@code /schema} companion so the model can ground its queries.
 *
 * <p>Like every tool here, both return a {@code String} (raw 2xx JSON passthrough or a JSON error
 * envelope) and never throw. The SQL guardrails are defense-in-depth plus predictable UX (clear
 * errors, a bounded result size) layered on top of OpenDota's already read-only DB role — not the
 * only thing standing between a bad query and the database.
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
            Pattern.compile("\\blimit\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_SEMICOLON = Pattern.compile(";\\s*$");
    /**
     * A top-level {@code "err":"..."} (OpenDota's SQL-error shape, which arrives as an HTTP 200),
     * matched only when no {@code "rows"} key precedes it so an {@code err} column inside a result
     * row isn't mistaken for the error. Group 1 is the (still JSON-escaped) error text.
     */
    private static final Pattern EXPLORER_ERR = Pattern.compile(
            "\\A\\s*\\{(?:(?!\"rows\").)*?\"err\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            Pattern.DOTALL);

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
            String body = client.getJson(path);
            String err = explorerError(body);
            if (err != null) {
                // /explorer 200s with {"err":...} on a SQL error; surface it as the standard error
                // envelope rather than passing the error body off as a success.
                return ToolResults.fromException(new OpenDotaException(422, "/explorer", err));
            }
            return body;
        } catch (OpenDotaException e) {
            return ToolResults.fromException(e);
        } catch (RuntimeException e) {
            return ToolResults.internalError("/explorer");
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
            return ToolResults.internalError(path);
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

    /** Append a {@code LIMIT} if absent, or clamp an existing trailing {@code LIMIT} to {@code cap}. */
    static String applyLimit(String sql, int cap) {
        Matcher m = TRAILING_LIMIT.matcher(sql);
        if (m.find()) {
            long requested = Long.parseLong(m.group(1));
            return m.replaceFirst("LIMIT " + Math.min(requested, cap));
        }
        return sql + " LIMIT " + cap;
    }

    /** The error text of an OpenDota explorer {@code {"err":...}} body, or null if it isn't one. */
    private static String explorerError(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = EXPLORER_ERR.matcher(body);
        return m.find() ? unescapeJson(m.group(1)) : null;
    }

    /** Minimal JSON-string unescaping, enough to render a readable error message. */
    private static String unescapeJson(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
