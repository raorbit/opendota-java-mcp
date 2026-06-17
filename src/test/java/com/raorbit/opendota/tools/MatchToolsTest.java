package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchToolsTest {

    @Test
    void getMatchBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{}");
        MatchTools tools = new MatchTools(client);

        tools.getMatch(123L);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/matches/123");
    }

    @Test
    void getProMatchesWithNullPagingHasNoQuery() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        MatchTools tools = new MatchTools(client);

        tools.getProMatches(null);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/proMatches");
    }

    @Test
    void getProMatchesWithPagingBuildsQuery() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        MatchTools tools = new MatchTools(client);

        tools.getProMatches(700000000L);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/proMatches?less_than_match_id=700000000");
    }

    @Test
    void getPublicMatchesBuildsFullQuery() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        MatchTools tools = new MatchTools(client);

        tools.getPublicMatches(700000000L, 70, 80);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        assertThat(path.getValue())
                .isEqualTo("/publicMatches?less_than_match_id=700000000&min_rank=70&max_rank=80");
    }

    @Test
    void getLiveGamesBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        MatchTools tools = new MatchTools(client);

        tools.getLiveGames();

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/live");
    }

    @Test
    void getLiveGamesReturnsErrorEnvelopeOnException() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString()))
                .thenThrow(new OpenDotaException(429, "/live", "rate limited"));
        MatchTools tools = new MatchTools(client);

        String result = tools.getLiveGames();

        assertThat(result)
                .contains("\"isError\":true")
                .contains("\"status\":429");
    }

    @Test
    void queryBuilderUrlEncodesValues() {
        // All current callers pass numerics (which encode to themselves), so the
        // encoding is otherwise unobservable. Exercise it directly with a value
        // containing query-significant characters.
        MatchTools.QueryBuilder qb = new MatchTools.QueryBuilder("/publicMatches");
        qb.append("note", "a b&c=d");

        assertThat(qb.build()).isEqualTo("/publicMatches?note=a+b%26c%3Dd");
    }

    @Test
    void getMatchReturnsInternalErrorEnvelopeOnRuntimeException() throws Exception {
        // An unexpected unchecked failure must still yield a clean envelope, not throw.
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenThrow(new IllegalStateException("boom"));
        MatchTools tools = new MatchTools(client);

        String result = tools.getMatch(123L);

        assertThat(result)
                .contains("\"isError\":true")
                .contains("\"status\":500")
                .contains("\"error\":\"internal error\"")
                .doesNotContain("boom");
    }
}
