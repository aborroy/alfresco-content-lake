package org.hyland.contentlake.rag.config;

import lombok.Data;
import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.client.HxprGraphApi;
import org.hyland.contentlake.client.HxprGraphService;
import org.hyland.contentlake.client.HxprQueryApi;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.client.HxprTokenProvider;
import org.hyland.contentlake.client.HxprVocabularyApi;
import org.hyland.contentlake.client.NamedQueryService;
import org.hyland.contentlake.client.VocabularyService;
import org.hyland.contentlake.rag.conversation.ConversationMemoryStore;
import org.hyland.contentlake.rag.conversation.InMemoryConversationMemoryStore;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.service.CommunitySummaryService;
import org.hyland.contentlake.rag.service.GraphAugmentationService;
import org.hyland.contentlake.rag.service.HybridSearchService;
import org.hyland.contentlake.rag.service.SourceMetadataResolver;
import org.hyland.contentlake.service.EmbeddingService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Clock;

/**
 * Central Spring configuration for the RAG service infrastructure.
 *
 * <p>Wires hxpr clients, embedding service, and token provider — the same
 * dependencies used by the batch-ingester but without ingestion-specific beans
 * (chunker, transform client, batch executor, etc.).</p>
 */
@Configuration
@EnableConfigurationProperties({
        RagAppConfig.HxprProperties.class
})
public class RagAppConfig {

    public static final String HXCS_REPOSITORY = "HXCS-REPOSITORY";

    // ----------------------------------------------------------------------
    // HXPR (Content Lake) wiring
    // ----------------------------------------------------------------------

    @Bean
    public HxprTokenProvider hxprTokenProvider(HxprProperties props) {
        HxprProperties.IdpConfig idp = props.getIdp();
        return new HxprTokenProvider(
                idp.getTokenUrl(),
                idp.getClientId(),
                idp.getClientSecret(),
                idp.getUsername(),
                idp.getPassword()
        );
    }

    @Bean
    public RestClient hxprRestClient(HxprProperties props, HxprTokenProvider tokenProvider) {
        return RestClient.builder()
                .baseUrl(props.getUrl())
                .requestInterceptor(hxprAuthInterceptor(props, tokenProvider))
                .build();
    }

    @Bean
    public HxprDocumentApi hxprDocumentApi(RestClient hxprRestClient) {
        return httpProxyFactory(hxprRestClient).createClient(HxprDocumentApi.class);
    }

    @Bean
    public HxprQueryApi hxprQueryApi(RestClient hxprRestClient) {
        return httpProxyFactory(hxprRestClient).createClient(HxprQueryApi.class);
    }

    /**
     * Vocabulary client. Registered here only — vocabulary normalization is consumed by
     * rag-service alone, so the ingester configs deliberately do not wire this bean.
     */
    @Bean
    public HxprVocabularyApi hxprVocabularyApi(RestClient hxprRestClient) {
        return httpProxyFactory(hxprRestClient).createClient(HxprVocabularyApi.class);
    }

    @Bean
    public VocabularyService vocabularyService(HxprVocabularyApi hxprVocabularyApi) {
        return new VocabularyService(hxprVocabularyApi);
    }

    @Bean
    public HxprService hxprService(HxprDocumentApi documentApi,
                                   HxprQueryApi queryApi,
                                   RestClient hxprRestClient) {
        return new HxprService(documentApi, queryApi, hxprRestClient);
    }

    @Bean
    public NamedQueryService namedQueryService(HxprService hxprService) {
        return new NamedQueryService(hxprService);
    }

    // ----------------------------------------------------------------------
    // GraphRAG (#55) - only wired when rag.graph.enabled=true
    // ----------------------------------------------------------------------

    @Bean
    @ConditionalOnProperty(name = "rag.graph.enabled", havingValue = "true")
    public HxprGraphApi hxprGraphApi(RestClient hxprRestClient) {
        return httpProxyFactory(hxprRestClient).createClient(HxprGraphApi.class);
    }

    @Bean
    @ConditionalOnProperty(name = "rag.graph.enabled", havingValue = "true")
    public HxprGraphService hxprGraphService(HxprGraphApi hxprGraphApi, RestClient hxprRestClient) {
        return new HxprGraphService(hxprGraphApi, hxprRestClient);
    }

    @Bean
    @ConditionalOnProperty(name = "rag.graph.enabled", havingValue = "true")
    public GraphAugmentationService graphAugmentationService(HxprGraphService hxprGraphService,
                                                             HybridSearchService hybridSearchService,
                                                             HxprService hxprService,
                                                             SourceMetadataResolver sourceMetadataResolver,
                                                             RagProperties ragProperties) {
        return new GraphAugmentationService(hxprGraphService, hybridSearchService, hxprService,
                sourceMetadataResolver, ragProperties);
    }

    @Bean
    @ConditionalOnProperty(name = "rag.graph.enabled", havingValue = "true")
    public CommunitySummaryService communitySummaryService(HxprGraphService hxprGraphService,
                                                           HxprService hxprService,
                                                           HxprDocumentApi hxprDocumentApi,
                                                           ChatModel chatModel,
                                                           RagProperties ragProperties) {
        return new CommunitySummaryService(hxprGraphService, hxprService, hxprDocumentApi, chatModel, ragProperties);
    }

    // ----------------------------------------------------------------------
    // Embedding service
    // ----------------------------------------------------------------------

    @Bean
    public EmbeddingService embeddingService(EmbeddingModel embeddingModel) {
        return new EmbeddingService(embeddingModel,
                embeddingModel.getClass().getSimpleName());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(ConversationMemoryStore.class)
    public ConversationMemoryStore conversationMemoryStore() {
        return new InMemoryConversationMemoryStore();
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private static ClientHttpRequestInterceptor hxprAuthInterceptor(HxprProperties props,
                                                                    HxprTokenProvider tokenProvider) {
        return (request, body, execution) -> {
            request.getHeaders().setBearerAuth(tokenProvider.getToken());
            request.getHeaders().set(HXCS_REPOSITORY, props.getRepositoryId());
            return execution.execute(request, body);
        };
    }

    private static HttpServiceProxyFactory httpProxyFactory(RestClient restClient) {
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();
    }

    // ----------------------------------------------------------------------
    // Configuration properties
    // ----------------------------------------------------------------------

    @Data
    @ConfigurationProperties(prefix = "hxpr")
    public static class HxprProperties {
        private String url = "http://localhost:8080";
        private String repositoryId = "default";
        private IdpConfig idp = new IdpConfig();

        @Data
        public static class IdpConfig {
            private String tokenUrl;
            private String clientId;
            private String clientSecret;
            private String username;
            private String password;
        }
    }
}
