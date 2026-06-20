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

class WriteToolsTest {

    @Test
    void requestParsePostsToRequestPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.postJson(anyString())).thenReturn("{\"job\":{\"jobId\":1}}");
        WriteTools tools = new WriteTools(client);

        tools.requestParse(123L);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).postJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/request/123");
    }

    @Test
    void refreshPlayerPostsToRefreshPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.postJson(anyString())).thenReturn("{}");
        WriteTools tools = new WriteTools(client);

        tools.refreshPlayer(5L);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).postJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/players/5/refresh");
    }

    @Test
    void getParseRequestGetsTheJobStatusPath() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.getJson(anyString())).thenReturn("{\"jobId\":42}");
        WriteTools tools = new WriteTools(client);

        tools.getParseRequest(42L);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getJson(path.capture());
        assertThat(path.getValue()).isEqualTo("/request/42");
    }

    @Test
    void mapsPostFailureToErrorEnvelope() throws Exception {
        OpenDotaClient client = mock(OpenDotaClient.class);
        when(client.postJson(anyString()))
                .thenThrow(new OpenDotaException(429, "/request/1", "rate limited"));
        WriteTools tools = new WriteTools(client);

        assertThat(tools.requestParse(1L))
                .contains("\"isError\":true").contains("\"status\":429");
    }
}
