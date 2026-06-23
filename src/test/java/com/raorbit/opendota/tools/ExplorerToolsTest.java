package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExplorerToolsTest {

    private static String capturedPath(OpenDotaClient client) throws Exception {
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        return path.getValue();
    }

    @Test
    void validQueryHitsExplorerWithAppendedLimit() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{\"rows\":[]}");
        ExplorerTools tools = new ExplorerTools(client);

        tools.runSqlExplorer("select 1", null);

        // No trailing LIMIT in the input → the default 200 is appended.
        assertThat(capturedPath(client)).isEqualTo("/explorer?sql=" + OpenDotaClient.encode("select 1 LIMIT 200"));
    }

    @Test
    void excessiveInSqlLimitIsClampedToTheEffectiveCap() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{\"rows\":[]}");
        ExplorerTools tools = new ExplorerTools(client);

        // row_limit=2000 (the hard cap) + an absurd in-SQL LIMIT → the in-SQL LIMIT is clamped down.
        tools.runSqlExplorer("SELECT 1 LIMIT 999999", 2000);

        assertThat(capturedPath(client)).isEqualTo("/explorer?sql=" + OpenDotaClient.encode("SELECT 1 LIMIT 2000"));
    }

    @Test
    void inSqlLimitIsClampedToTheDefaultCapWhenNoRowLimitGiven() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{\"rows\":[]}");
        ExplorerTools tools = new ExplorerTools(client);

        // No row_limit → default cap 200, so even LIMIT 999999 is clamped to 200.
        tools.runSqlExplorer("SELECT 1 LIMIT 999999", null);

        assertThat(capturedPath(client)).isEqualTo("/explorer?sql=" + OpenDotaClient.encode("SELECT 1 LIMIT 200"));
    }

    @Test
    void rowLimitArgClampedToCapAndAppended() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{\"rows\":[]}");
        ExplorerTools tools = new ExplorerTools(client);

        tools.runSqlExplorer("SELECT 1", 50000);

        assertThat(capturedPath(client)).isEqualTo("/explorer?sql=" + OpenDotaClient.encode("SELECT 1 LIMIT 2000"));
    }

    @Test
    void schemaToolBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        ExplorerTools tools = new ExplorerTools(client);

        tools.getSqlSchema();

        assertThat(capturedPath(client)).isEqualTo("/schema");
    }

    @Test
    void rejectsComments() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        ExplorerTools tools = new ExplorerTools(client);

        String result = tools.runSqlExplorer("SELECT 1 -- sneaky", null);

        assertThat(result).contains("\"isError\":true").contains("\"status\":400").contains("comments");
        verify(client, never()).getJson(anyString());
    }

    @Test
    void rejectsMultipleStatements() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        ExplorerTools tools = new ExplorerTools(client);

        String result = tools.runSqlExplorer("SELECT 1; SELECT 2", null);

        assertThat(result).contains("\"isError\":true").contains("single statement");
        verify(client, never()).getJson(anyString());
    }

    @Test
    void allowsOneTrailingSemicolon() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{\"rows\":[]}");
        ExplorerTools tools = new ExplorerTools(client);

        tools.runSqlExplorer("SELECT 1;", null);

        assertThat(capturedPath(client)).isEqualTo("/explorer?sql=" + OpenDotaClient.encode("SELECT 1 LIMIT 200"));
    }

    @Test
    void rejectsNonSelect() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        ExplorerTools tools = new ExplorerTools(client);

        String result = tools.runSqlExplorer("UPDATE players SET name='x'", null);

        assertThat(result).contains("\"isError\":true").contains("SELECT / WITH");
        verify(client, never()).getJson(anyString());
    }

    @Test
    void rejectsBlockedKeywordInSelectBody() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        ExplorerTools tools = new ExplorerTools(client);

        // Starts with SELECT (passes the start check) but smuggles a dangerous function.
        String result = tools.runSqlExplorer("SELECT pg_sleep(10)", null);

        assertThat(result).contains("\"isError\":true").contains("disallowed keyword");
        verify(client, never()).getJson(anyString());
    }

    @Test
    void wholeWordBlocklistDoesNotRejectOffsetOrInnerWords() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{\"rows\":[]}");
        ExplorerTools tools = new ExplorerTools(client);

        // "offset" contains "set", "inner"/"into-free" must not trip the \b blocklist.
        tools.runSqlExplorer("SELECT account_id FROM player_matches ORDER BY account_id OFFSET 5", null);

        assertThat(capturedPath(client)).contains("OFFSET+5");
        verify(client).getJson(anyString());
    }

    @Test
    void nullSqlReturnsBadArgWithoutCallingClient() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        ExplorerTools tools = new ExplorerTools(client);

        String result = tools.runSqlExplorer(null, null);

        assertThat(result).contains("\"isError\":true").contains("\"status\":400")
                .contains("\"endpoint\":\"/explorer\"");
        verify(client, never()).getJson(anyString());
    }

    @Test
    void explorerInBodyErrIsSurfacedAsCleanEnvelope() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        // /explorer returns HTTP 200 with {err} on a SQL error.
        when(client.getJson(anyString())).thenReturn("{\"err\":\"column \\\"foo\\\" does not exist\"}");
        ExplorerTools tools = new ExplorerTools(client);

        String result = tools.runSqlExplorer("SELECT foo FROM matches", null);

        assertThat(result)
                .contains("\"isError\":true")
                .contains("\"status\":422")
                .contains("\"endpoint\":\"/explorer\"")
                .contains("does not exist");
    }

    @Test
    void resultRowContainingErrColumnIsNotMistakenForAnError() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        String body = "{\"rowCount\":1,\"rows\":[{\"err\":\"some value\"}],\"fields\":[{\"name\":\"err\"}]}";
        when(client.getJson(anyString())).thenReturn(body);
        ExplorerTools tools = new ExplorerTools(client);

        String result = tools.runSqlExplorer("SELECT err FROM foo", null);

        // Only a TOP-LEVEL err is the error shape; an err *column* under rows[] is real data, so the
        // body is shaped (not an error envelope) and the row + field name are preserved.
        assertThat(result)
                .doesNotContain("\"isError\"")
                .contains("\"fields\":[\"err\"]")
                .contains("\"rows\":[{\"err\":\"some value\"}]");
    }

    @Test
    void shapesAwayNodePostgresInternals() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        // A realistic /explorer body: node-postgres's raw result object with all its internals.
        // NOTE the top-level "err":null — a SUCCESSFUL response carries it, so the shaper must treat
        // null err as success (not surface a 422).
        String raw = "{\"command\":\"SELECT\",\"rowCount\":1,\"oid\":null,\"err\":null,"
                + "\"rows\":[{\"n\":250422}],"
                + "\"fields\":[{\"name\":\"n\",\"tableID\":0,\"columnID\":0,\"dataTypeID\":20}],"
                + "\"_parsers\":[null],\"_types\":{\"builtins\":{\"BOOL\":16,\"INT8\":20}},"
                + "\"RowCtor\":null,\"_prebuiltEmptyResultObject\":{\"n\":null}}";
        when(client.getJson(anyString())).thenReturn(raw);
        ExplorerTools tools = new ExplorerTools(client);

        String result = tools.runSqlExplorer("SELECT count(*) AS n FROM matches", 200);

        assertThat(result)
                .doesNotContain("\"isError\"")
                .contains("\"command\":\"SELECT\"")
                .contains("\"rowCount\":1")
                .contains("\"fields\":[\"n\"]")
                .contains("\"rows\":[{\"n\":250422}]")
                .contains("\"sql_executed\":\"SELECT count(*) AS n FROM matches LIMIT 200\"")
                .doesNotContain("\"err\"")
                .doesNotContain("_types")
                .doesNotContain("_parsers")
                .doesNotContain("dataTypeID")
                .doesNotContain("RowCtor")
                .doesNotContain("_prebuiltEmptyResultObject")
                .doesNotContain("oid");
    }

    @Test
    void nullTopLevelErrIsSuccessNotAnError() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        // The exact regression: a successful query whose body carries "err":null must NOT 422.
        when(client.getJson(anyString())).thenReturn("{\"command\":\"SELECT\",\"err\":null,\"rows\":[{\"x\":1}]}");
        ExplorerTools tools = new ExplorerTools(client);

        String result = tools.runSqlExplorer("SELECT 1 AS x", null);

        assertThat(result)
                .doesNotContain("\"isError\"")
                .contains("\"rows\":[{\"x\":1}]");
    }

    @Test
    void applyLimitAppendsWhenAbsentAndClampsWhenPresent() {
        assertThat(ExplorerTools.applyLimit("SELECT 1", 200)).isEqualTo("SELECT 1 LIMIT 200");
        assertThat(ExplorerTools.applyLimit("SELECT 1 LIMIT 5", 200)).isEqualTo("SELECT 1 LIMIT 5");
        assertThat(ExplorerTools.applyLimit("SELECT 1 limit 999999", 2000)).isEqualTo("SELECT 1 LIMIT 2000");
        
        // Handles trailing OFFSET preservation
        assertThat(ExplorerTools.applyLimit("SELECT 1 LIMIT 5 OFFSET 10", 200)).isEqualTo("SELECT 1 LIMIT 5 OFFSET 10");
        assertThat(ExplorerTools.applyLimit("SELECT 1 LIMIT 999999 offset 10", 2000)).isEqualTo("SELECT 1 LIMIT 2000 offset 10");
    }
}
