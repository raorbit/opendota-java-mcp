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

class MiscToolsTest {

    private static String capture(OpenDotaClient client) throws Exception {
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        return path.getValue();
    }

    @Test
    void getRecordsBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        new MiscTools(client).getRecords("kills");
        assertThat(capture(client)).isEqualTo("/records/kills");
    }

    @Test
    void getRecordsWithBlankFieldReturnsBadArgWithoutCallingClient() {
        OpenDotaClient client = mock(OpenDotaClient.class);
        String result = new MiscTools(client).getRecords("  ");
        assertThat(result).contains("\"isError\":true").contains("\"status\":400");
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void getParsedMatchesBuildsPathWithoutQueryWhenUnpaged() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        new MiscTools(client).getParsedMatches(null);
        assertThat(capture(client)).isEqualTo("/parsedMatches");
    }

    @Test
    void getParsedMatchesAppendsPagingCursor() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("[]");
        new MiscTools(client).getParsedMatches(7000000000L);
        assertThat(capture(client)).isEqualTo("/parsedMatches?less_than_match_id=7000000000");
    }

    @Test
    void getMetadataBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{}");
        new MiscTools(client).getMetadata();
        assertThat(capture(client)).isEqualTo("/metadata");
    }

    @Test
    void getHealthBuildsPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{}");
        new MiscTools(client).getHealth();
        assertThat(capture(client)).isEqualTo("/health");
    }
}
