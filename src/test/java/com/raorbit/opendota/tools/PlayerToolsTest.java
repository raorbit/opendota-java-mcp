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

    private static String capture(OpenDotaClient client) throws Exception {
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).getJson(path.capture());
        return path.getValue();
    }

    @Test
    void getPlayerWlBuildsExpectedPathWithOnlyNonNullParams() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // account_id=123, limit=5, win=1, everything else null (full 18-filter set).
        tools.getPlayerWl(123L, 5, null, 1, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        assertThat(capture(client)).isEqualTo("/players/123/wl?limit=5&win=1");
    }

    @Test
    void getPlayerMatchesBuildsQueryIncludingSortString() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // limit=10, offset=20, sort="kills" — exercises the lone String filter through appendParam.
        tools.getPlayerMatches(123L, 10, 20, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "kills", null);

        assertThat(capture(client)).isEqualTo("/players/123/matches?limit=10&offset=20&sort=kills");
    }

    @Test
    void getPlayerMatchesExpandsProjectAndAppendsFilters() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // lane_role=2, hero_id=1, is_radiant=1, with_hero_id="2", against_hero_id="3" (CSV arrays),
        // plus a comma-separated project list that expands into one project=<field> per token.
        tools.getPlayerMatches(123L, null, null, null, null, null, null, null, null, 2, 1, 1,
                null, null, "2", "3", null, null, null, "kills,deaths,hero_id");

        assertThat(capture(client)).isEqualTo(
                "/players/123/matches?lane_role=2&hero_id=1&is_radiant=1&with_hero_id=2&against_hero_id=3"
                        + "&project=kills&project=deaths&project=hero_id");
    }

    @Test
    void getPlayerMatchesIncludesNewlyAddedFilters() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // region, excluded_account_id (CSV), significant, having — the four added in M2.
        tools.getPlayerMatches(123L, null, null, null, null, null, null, 8, null, null, null, null,
                null, "99", null, null, 0, 5, null, null);

        assertThat(capture(client))
                .isEqualTo("/players/123/matches?region=8&excluded_account_id=99&significant=0&having=5");
    }

    @Test
    void getPlayerMatchesIgnoresBlankProjectTokens() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // Empty/whitespace tokens in the project CSV are dropped, not emitted as project=.
        tools.getPlayerMatches(9L, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, " kills , , deaths ");

        assertThat(capture(client)).isEqualTo("/players/9/matches?project=kills&project=deaths");
    }

    @Test
    void getPlayerHeroesBuildsQueryWithFilters() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // limit=3, all other filters null.
        tools.getPlayerHeroes(7L, 3, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        assertThat(capture(client)).isEqualTo("/players/7/heroes?limit=3");
    }

    @Test
    void getPlayerHeroesAcceptsTheSharedFilterSet() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        // The point of M2: an aggregation tool can now be filtered, e.g. "vs hero 5 in the offlane".
        tools.getPlayerHeroes(7L, null, null, null, null, null, null, null, null, 2, null, null,
                null, null, null, "5", null, null, null);

        assertThat(capture(client)).isEqualTo("/players/7/heroes?lane_role=2&against_hero_id=5");
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

        // OpenDotaClient.encode is the real static method; a space encodes as '+'.
        tools.searchPlayers("de ndi");

        assertThat(capture(client)).isEqualTo("/search?q=de+ndi");
    }

    @Test
    void getPlayerRecentMatchesBuildsPathWithoutQuery() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        tools.getPlayerRecentMatches(88L);

        assertThat(capture(client)).isEqualTo("/players/88/recentMatches");
    }

    @Test
    void getPlayerPeersBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        tools.getPlayerPeers(42L, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        assertThat(capture(client)).isEqualTo("/players/42/peers");
    }

    @Test
    void getPlayerTotalsBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        tools.getPlayerTotals(42L, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        assertThat(capture(client)).isEqualTo("/players/42/totals");
    }

    @Test
    void playerSubResourcesBuildPaths() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        PlayerTools tools = new PlayerTools(client);

        tools.getPlayerRatings(5L);
        tools.getPlayerRankings(5L);
        tools.getPlayerCounts(5L);
        tools.getPlayerHistograms(5L, "kills");
        tools.getPlayerPros(5L);
        tools.getPlayerWardmap(5L);
        tools.getPlayerWordcloud(5L);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(7)).getJson(path.capture());
        assertThat(path.getAllValues()).containsExactly(
                "/players/5/ratings", "/players/5/rankings", "/players/5/counts",
                "/players/5/histograms/kills", "/players/5/pros", "/players/5/wardmap", "/players/5/wordcloud");
    }

    @Test
    void getPlayerHistogramsWithBlankFieldReturnsBadArg() {
        OpenDotaClient client = mock(OpenDotaClient.class);
        PlayerTools tools = new PlayerTools(client);

        assertThat(tools.getPlayerHistograms(5L, " "))
                .contains("\"isError\":true").contains("\"status\":400");
        org.mockito.Mockito.verifyNoInteractions(client);
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

    @Test
    void getPlayerReturnsInternalErrorEnvelopeOnRuntimeException() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenThrow(new IllegalStateException("boom"));
        PlayerTools tools = new PlayerTools(client);

        String result = tools.getPlayer(123L);

        assertThat(result)
                .contains("\"isError\":true")
                .contains("\"status\":500")
                .contains("\"error\":\"internal error\"")
                .doesNotContain("boom");
    }
}
