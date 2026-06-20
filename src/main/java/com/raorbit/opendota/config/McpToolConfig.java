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
import com.raorbit.opendota.tools.WriteTools;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpenDotaProperties.class)
public class McpToolConfig {

    @Bean(destroyMethod = "close")
    OpenDotaClient openDotaClient(OpenDotaProperties properties) {
        if (properties.isSidecarEnabled() && properties.isWriteToolsEnabled()) {
            // The shared sidecar only proxies GETs, so a forwarded write tool would just 405. Fail fast
            // at startup (before any tool call) rather than letting writes silently break in this combo.
            throw new IllegalStateException("opendota.write-tools-enabled is not supported together with "
                    + "opendota.sidecar-enabled: the shared sidecar only proxies GET requests. Run the write "
                    + "tools on a direct (non-forwarding) server instead.");
        }
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
                                       ProTools proTools, MiscTools miscTools,
                                       ObjectProvider<WriteTools> writeTools) {
        List<Object> tools = new ArrayList<>(List.of(playerTools, matchTools, heroTools, explorerTools,
                teamTools, leagueTools, proTools, miscTools));
        // The write tools are flag-gated: their bean exists only when opendota.write-tools-enabled=true,
        // so by default this adds nothing and they never appear in tools/list.
        writeTools.ifAvailable(tools::add);
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
    }
}
