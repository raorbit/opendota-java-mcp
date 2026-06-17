package com.raorbit.opendota.config;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.tools.HeroTools;
import com.raorbit.opendota.tools.MatchTools;
import com.raorbit.opendota.tools.PlayerTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfig {

    @Bean
    OpenDotaClient openDotaClient() {
        return new OpenDotaClient(System.getenv("OPENDOTA_API_KEY"));
    }

    @Bean
    ToolCallbackProvider opendotaTools(PlayerTools playerTools, MatchTools matchTools, HeroTools heroTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(playerTools, matchTools, heroTools)
                .build();
    }
}
