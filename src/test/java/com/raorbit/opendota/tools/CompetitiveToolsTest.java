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

/** Path-building tests for the curated team / league / pro tools (M4). */
class CompetitiveToolsTest {

    private static String capture(OpenDotaClient client) throws Exception {
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        return path.getValue();
    }

    @Test
    void getTeamBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{}");
        assertThat(run(() -> new TeamTools(client).getTeam(15L), client)).isEqualTo("/teams/15");
    }

    @Test
    void getTeamMatchesBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        assertThat(run(() -> new TeamTools(client).getTeamMatches(15L), client)).isEqualTo("/teams/15/matches");
    }

    @Test
    void getLeagueBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{}");
        assertThat(run(() -> new LeagueTools(client).getLeague(4210L), client)).isEqualTo("/leagues/4210");
    }

    @Test
    void getLeagueMatchesBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        assertThat(run(() -> new LeagueTools(client).getLeagueMatches(4210L), client))
                .isEqualTo("/leagues/4210/matches");
    }

    @Test
    void getProPlayersBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        assertThat(run(() -> new ProTools(client).getProPlayers(), client)).isEqualTo("/proPlayers");
    }

    @Test
    void getTopPlayersBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        assertThat(run(() -> new ProTools(client).getTopPlayers(), client)).isEqualTo("/topPlayers");
    }

    @Test
    void getTeamsBuildsPathWithOptionalPage() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        assertThat(run(() -> new TeamTools(client).getTeams(null), client)).isEqualTo("/teams");

        OpenDotaClient paged = mock(OpenDotaClient.class);
        when(paged.getJson(anyString())).thenReturn("[]");
        assertThat(run(() -> new TeamTools(paged).getTeams(2), paged)).isEqualTo("/teams?page=2");
    }

    @Test
    void getTeamPlayersBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        assertThat(run(() -> new TeamTools(client).getTeamPlayers(15L), client)).isEqualTo("/teams/15/players");
    }

    @Test
    void getTeamHeroesBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        assertThat(run(() -> new TeamTools(client).getTeamHeroes(15L), client)).isEqualTo("/teams/15/heroes");
    }

    @Test
    void getLeaguesBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        assertThat(run(() -> new LeagueTools(client).getLeagues(), client)).isEqualTo("/leagues");
    }

    @Test
    void getLeagueTeamsBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        assertThat(run(() -> new LeagueTools(client).getLeagueTeams(4210L), client)).isEqualTo("/leagues/4210/teams");
    }

    @Test
    void mapsUpstreamFailureToErrorEnvelope() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString()))
                .thenThrow(new OpenDotaException(404, "/teams/15", "Not Found"));

        assertThat(new TeamTools(client).getTeam(15L))
                .contains("\"isError\":true").contains("\"status\":404");
    }

    /** Run a tool call, then return the single path it passed to the (already-stubbed) client. */
    private static String run(Runnable call, OpenDotaClient client) throws Exception {
        call.run();
        return capture(client);
    }
}
