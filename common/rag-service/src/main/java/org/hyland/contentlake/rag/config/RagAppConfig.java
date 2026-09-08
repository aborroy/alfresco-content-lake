package org.hyland.contentlake.rag.config;

import lombok.Data;
import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.client.HxprQueryApi;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.client.HxprVocabularyApi;
import org.hyland.contentlake.client.NamedQueryService;
import org.hyland.contentlake.client.VocabularyService;
import org.hyland.contentlake.rag.conversation.ConversationMemoryStore;
import org.hyland.contentlake.rag.conversation.InMemoryConversationMemoryStore;
import org.hyland.contentlake.service.EmbeddingService;
import org.hyland.contentlake.service.EmbeddingTypeResolver;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
    public RestClient hxprRestClient(HxprProperties props) {
        return RestClient.builder()
                .baseUrl(props.getUrl())
                .requestInterceptor(hxprAuthInterceptor(props))
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
                                   RestClient hxprRestClient,
                                   @Value("${spring.ai.openai.embedding.model:}") String embeddingModelName) {
        return new HxprService(documentApi, queryApi, hxprRestClient,
                EmbeddingTypeResolver.toEmbeddingType(embeddingModelName));
    }

    @Bean
    public NamedQueryService namedQueryService(HxprService hxprService) {
        return new NamedQueryService(hxprService);
    }

    // ----------------------------------------------------------------------
    // Embedding service
    // ----------------------------------------------------------------------

    /**
     * The model name must be the configured model, not the implementation class name: it is
     * reported as the embedding model on search responses and it is the value the ingesters derive
     * the hxpr embedding type from, so a class name here made rag-service disagree with every
     * writer about which model the corpus was embedded under.
     */
    @Bean
    public EmbeddingService embeddingService(
            EmbeddingModel embeddingModel,
            @Value("${spring.ai.openai.embedding.model:}") String embeddingModelName) {
        return new EmbeddingService(embeddingModel, embeddingModelName);
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

    private static ClientHttpRequestInterceptor hxprAuthInterceptor(HxprProperties props) {
        return (request, body, execution) -> {
            request.getHeaders().setBasicAuth(props.getUsername(), props.getPassword());
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

        /** HTTP Basic credentials for the ai-ready-index engine (filestore user store). */
        private String username;
        private String password;
    }
}
