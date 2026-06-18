package com.raorbit.opendota.config;

import com.raorbit.opendota.client.OpenDotaClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link McpToolConfig} client wiring, exercising the
 * direct-vs-forwarding branch without booting a Spring context.
 */
class McpToolConfigTest {

    @Test
    void buildsForwardingClientWhenSidecarEnabled() {
        OpenDotaProperties props = new OpenDotaProperties();
        props.setSidecarEnabled(true);
        props.setSidecarHost("127.0.0.1");
        props.setSidecarPort(31337);

        try (OpenDotaClient client = new McpToolConfig().openDotaClient(props)) {
            // A forwarding client holds no API key (the sidecar does), so it is keyless
            // regardless of any OPENDOTA_API_KEY in the environment.
            assertThat(client.isKeyed()).isFalse();
        }
    }

    @Test
    void buildsDirectClientWhenSidecarDisabled() {
        OpenDotaProperties props = new OpenDotaProperties();   // sidecarEnabled defaults to false

        try (OpenDotaClient client = new McpToolConfig().openDotaClient(props)) {
            assertThat(client).isNotNull();
        }
    }
}
