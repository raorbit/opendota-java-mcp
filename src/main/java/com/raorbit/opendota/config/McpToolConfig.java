package com.raorbit.opendota.config;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.tools.ExplorerTools;
import com.raorbit.opendota.tools.HeroTools;
import com.raorbit.opendota.tools.LeagueTools;
import com.raorbit.opendota.tools.MatchTools;
import com.raorbit.opendota.tools.MiscTools;
import com.raorbit.opendota.tools.PlayerTools;
import com.raorbit.opendota.tools.ProTools;
import com.raorbit.opendota.tools.TeamTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpenDotaProperties.class)
public class McpToolConfig {

    @Bean(destroyMethod = "close")
    OpenDotaClient openDotaClient(OpenDotaProperties properties) {
        if (properties.isSidecarEnabled()) {
            // Forward every call to the shared local sidecar, which holds the API key
            // and owns the single rate limiter and cache. This server keeps no key.
            String baseUrl = "http://" + properties.getSidecarHost() + ":" + properties.getSidecarPort() + "/api";
            return OpenDotaClient.forwardingTo(baseUrl, properties.getMaxResponseBytes(),
                    properties.getSidecarToken());
        }
        return new OpenDotaClient(System.getenv("OPENDOTA_API_KEY"),
                properties.getCacheMaxEntries(), properties.getCacheMaxBytes(),
                properties.getRateLimitBudget(), properties.getMaxResponseBytes(),
                properties.getRateLimitPermitsPerMinute());
    }

    @Bean
    ToolCallbackProvider opendotaTools(PlayerTools playerTools, MatchTools matchTools, HeroTools heroTools,
                                       ExplorerTools explorerTools, TeamTools teamTools, LeagueTools leagueTools,
                                       ProTools proTools, MiscTools miscTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(playerTools, matchTools, heroTools, explorerTools, teamTools, leagueTools,
                        proTools, miscTools)
                .build();
    }
}
