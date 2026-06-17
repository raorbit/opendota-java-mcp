package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerToolsTest {

    @Test
    void getPlayerWlBuildsExpectedPathWithOnlyNonNullParams() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // account_id=123, limit=5, offset=null, hero_id=null, win=1, rest null.
        tools.getPlayerWl(123L, 5, null, null, 1, null, null, null);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/players/123/wl?limit=5&win=1");
    }

    @Test
    void getPlayerMatchesBuildsQueryIncludingSortString() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // limit=10, offset=20, rest null, sort="kills" — exercises the lone String
        // filter through appendParam (which URL-encodes the value).
        tools.getPlayerMatches(123L, 10, 20, null, null, null, null, null, "kills");

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/players/123/matches?limit=10&offset=20&sort=kills");
    }

    @Test
    void getPlayerHeroesBuildsQueryWithFilters() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // getPlayerHeroes(account_id, limit, date); date null is skipped.
        tools.getPlayerHeroes(7L, 3, null);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/players/7/heroes?limit=3");
    }

    @Test
    void searchPlayersWithBlankQueryReturnsBadArgWithoutCallingClient() {
        OpenDotaClient client = mock(OpenDotaClient.class);
        PlayerTools tools = new PlayerTools(client);

        String result = tools.searchPlayers("   ");

        assertThat(result)
                .contains("\"isError\":true")
                .contains("\"status\":400");
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void searchPlayersEncodesQuery() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // OpenDotaClient.encode is the real static method (Mockito does not stub
        // statics). It delegates to URLEncoder.encode with UTF-8, which uses
        // application/x-www-form-urlencoded rules: a space encodes as '+'.
        tools.searchPlayers("de ndi");

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/search?q=de+ndi");
    }

    @Test
    void getPlayerRecentMatchesBuildsPathWithoutQuery() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        tools.getPlayerRecentMatches(88L);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/players/88/recentMatches");
    }

    @Test
    void getPlayerReturnsErrorEnvelopeOnException() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString()))
                .thenThrow(new OpenDotaException(429, "/players/123", "rate limited"));
        PlayerTools tools = new PlayerTools(client);

        String result = tools.getPlayer(123L);

        assertThat(result)
                .contains("\"isError\":true")
                .contains("\"status\":429");
    }
}
