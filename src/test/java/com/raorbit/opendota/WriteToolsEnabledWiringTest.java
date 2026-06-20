package com.raorbit.opendota;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code opendota.write-tools-enabled=true} the {@code WriteTools} bean is created and its three
 * tools are registered. The complementary default-off case is asserted in {@link McpWiringTest}.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.server.enabled=false",
        "opendota.write-tools-enabled=true",
})
class WriteToolsEnabledWiringTest {

    @Autowired
    ApplicationContext context;

    @Test
    void writeToolsAreRegisteredWhenEnabled() {
        ToolCallbackProvider provider = context.getBean(ToolCallbackProvider.class);
        List<String> names = Arrays.stream(provider.getToolCallbacks())
                .map(cb -> cb.getToolDefinition().name())
                .toList();

        assertThat(names).contains("request_parse", "refresh_player", "get_parse_request");
    }
}
