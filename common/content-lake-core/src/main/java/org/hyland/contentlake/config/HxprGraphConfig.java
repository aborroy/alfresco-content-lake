package org.hyland.contentlake.config;

import org.hyland.contentlake.client.HxprGraphApi;
import org.hyland.contentlake.client.HxprGraphService;
import org.hyland.contentlake.service.GraphIngestionService;
import org.hyland.contentlake.service.GraphProvisioningService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Wires the hxpr Graph API client and the GraphRAG provisioning service.
 *
 * <p>Active only when {@code hxpr.graph.enabled=true}. It reuses the application's shared
 * {@code hxprRestClient} bean (which already carries the HTTP Basic credentials and
 * {@code HXCS-REPOSITORY} header), so the graph proxy inherits authentication automatically.
 * When disabled, no graph beans are created and the module is entirely inert.</p>
 */
@Configuration
@ConditionalOnProperty(name = "hxpr.graph.enabled", havingValue = "true")
public class HxprGraphConfig {

    @Bean
    public HxprGraphApi hxprGraphApi(RestClient hxprRestClient) {
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(hxprRestClient))
                .build()
                .createClient(HxprGraphApi.class);
    }

    @Bean
    public HxprGraphService hxprGraphService(HxprGraphApi hxprGraphApi, RestClient hxprRestClient) {
        return new HxprGraphService(hxprGraphApi, hxprRestClient);
    }

    @Bean
    public GraphProvisioningService graphProvisioningService(HxprGraphService hxprGraphService,
                                                             HxprProperties hxprProperties,
                                                             ResourceLoader resourceLoader) {
        return new GraphProvisioningService(hxprGraphService, hxprProperties, resourceLoader);
    }

    /**
     * Entity-extraction collaborator for ingesters (#54). Uses the auto-configured Spring AI
     * {@link ChatModel}. Ingester {@code NodeSyncService} beans pick it up via {@code ObjectProvider}
     * (null when graph is disabled).
     */
    @Bean
    public GraphIngestionService graphIngestionService(ChatModel chatModel,
                                                       HxprGraphService hxprGraphService,
                                                       HxprProperties hxprProperties) {
        return new GraphIngestionService(chatModel, hxprGraphService, hxprProperties);
    }
}
