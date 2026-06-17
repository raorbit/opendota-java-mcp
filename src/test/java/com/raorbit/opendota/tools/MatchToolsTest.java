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
}
