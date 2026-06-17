package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeroToolsTest {

    @Test
    void listHeroesBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        HeroTools tools = new HeroTools(client);

        tools.listHeroes();

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/heroes");
    }

    @Test
    void getHeroStatsBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        HeroTools tools = new HeroTools(client);

        tools.getHeroStats();

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/heroStats");
    }

    @Test
    void getHeroRankingsBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        HeroTools tools = new HeroTools(client);

        tools.getHeroRankings("14");

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/rankings?hero_id=14");
    }

    @Test
    void getConstantsBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{}");
        HeroTools tools = new HeroTools(client);

        tools.getConstants("items");

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/constants/items");
    }

    @Test
    void getHeroRankingsWithNullReturnsBadArgWithoutCallingClient() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        HeroTools tools = new HeroTools(client);

        String result = tools.getHeroRankings(null);

        assertThat(result)
                .contains("\"isError\":true")
                .contains("\"status\":400")
                .contains("\"endpoint\":\"/rankings\"");
        verify(client, never()).getJson(anyString());
    }

    @Test
    void getHeroRankingsReturnsErrorEnvelopeOnException() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString()))
                .thenThrow(new OpenDotaException(429, "/rankings?hero_id=14", "rate limited"));
        HeroTools tools = new HeroTools(client);

        String result = tools.getHeroRankings("14");

        assertThat(result)
                .contains("\"isError\":true")
                .contains("\"status\":429");
    }
}
