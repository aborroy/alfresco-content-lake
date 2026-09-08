package org.hyland.alfresco.contentlake.live.config;

import lombok.Data;
import org.hyland.alfresco.contentlake.client.AlfrescoClient;
import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.client.HxprQueryApi;
import org.hyland.contentlake.client.HxprService;
import org.hyland.alfresco.contentlake.client.TransformClient;
import org.hyland.contentlake.config.HxprProperties;
import org.hyland.alfresco.contentlake.config.TransformProperties;
import org.hyland.alfresco.contentlake.service.ContentLakeScopeResolver;
import org.hyland.contentlake.service.EmbeddingService;
import org.hyland.contentlake.service.EmbeddingTypeResolver;
import org.hyland.contentlake.service.IndexProofService;
import org.hyland.contentlake.service.NodeSyncService;
import org.hyland.contentlake.service.chunking.NoiseReductionService;
import org.hyland.contentlake.service.chunking.SimpleChunkingService;
import org.hyland.contentlake.service.chunking.strategy.ChunkingStrategy.ChunkingConfig;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Central Spring configuration for the live-ingester module.
 *
 * <p>Wires the shared ingestion infrastructure and lets the Alfresco Java SDK
 * starter own the Event2 ActiveMQ subscription.</p>
 */
@Configuration
@EnableConfigurationProperties({
        LiveIngesterProperties.class,
        HxprProperties.class,
        TransformProperties.class,
        LiveIngesterConfig.EmbeddingProperties.class
})
public class LiveIngesterConfig {

    public static final String HXCS_REPOSITORY = "HXCS-REPOSITORY";

    // ──────────────────────────────────────────────────────────────────────
    // HXPR (Content Lake) wiring
    // ──────────────────────────────────────────────────────────────────────

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

    @Bean
    public HxprService hxprService(HxprDocumentApi documentApi, HxprQueryApi queryApi,
                                   RestClient hxprRestClient, EmbeddingProperties props) {
        return new HxprService(documentApi, queryApi, hxprRestClient,
                EmbeddingTypeResolver.toEmbeddingType(props.getModelName()));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Transform Service
    // ──────────────────────────────────────────────────────────────────────

    @Bean
    public TransformClient transformClient(TransformProperties props) {
        return new TransformClient(props.getUrl(), props.getTimeoutMs());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Embedding pipeline
    // ──────────────────────────────────────────────────────────────────────

    @Bean
    public EmbeddingService embeddingService(EmbeddingModel embeddingModel, EmbeddingProperties props) {
        return new EmbeddingService(embeddingModel, props.getModelName());
    }

    /**
     * Required even though this ingester exposes no index-proof route: it shares
     * {@code ContentLakeNodeStatusService} with the batch ingester, and that service takes this
     * collaborator, so the context does not start without it.
     */
    @Bean
    public IndexProofService indexProofService(HxprService hxprService, EmbeddingService embeddingService) {
        return new IndexProofService(hxprService, embeddingService);
    }

    @Bean
    public NoiseReductionService noiseReductionService() {
        return new NoiseReductionService(false);
    }

    @Bean
    public ChunkingConfig chunkingConfig(LiveIngesterProperties props) {
        LiveIngesterProperties.Chunking ch = props.getChunking();
        return new ChunkingConfig(
                ch.getMinChunkSize(),
                ch.getMaxChunkSize(),
                ch.getOverlapSize(),
                ch.getSimilarityThreshold()
        );
    }

    @Bean
    public SimpleChunkingService chunkingService(NoiseReductionService nr, ChunkingConfig cfg) {
        return new SimpleChunkingService(nr, cfg);
    }

    @Bean(name = "statusLookupExecutor")
    public Executor statusLookupExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public ContentLakeScopeResolver contentLakeScopeResolver(LiveIngesterProperties props,
                                                              AlfrescoClient alfrescoClient) {
        return new ContentLakeScopeResolver(
                props.getFilter().getExcludePaths(),
                props.getFilter().getExcludeAspects(),
                alfrescoClient
        );
    }

    // ──────────────────────────────────────────────────────────────────────
    // NodeSyncService (shared pipeline)
    // ──────────────────────────────────────────────────────────────────────

    @Bean
    public NodeSyncService nodeSyncService(
            AlfrescoClient alfrescoClient,
            HxprDocumentApi documentApi,
            HxprService hxprService,
            TransformClient transformClient,
            EmbeddingService embeddingService,
            SimpleChunkingService chunkingService,
            HxprProperties hxprProps,
            @org.springframework.beans.factory.annotation.Value(
                    "${content-lake.ingest.keyword-context-enrichment-enabled:false}")
            boolean keywordContextEnrichmentEnabled
    ) {
        return new NodeSyncService(
                alfrescoClient,    // ContentSourceClient
                documentApi,
                hxprService,
                transformClient,   // TextExtractor
                embeddingService,
                chunkingService,
                hxprProps.getTargetPath(),
                hxprProps.getPathRepositoryId(),
                keywordContextEnrichmentEnabled
        );
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

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

    // ──────────────────────────────────────────────────────────────────────
    // Module-local configuration properties
    // ──────────────────────────────────────────────────────────────────────

    /** Embedding model name, separate from chunking config. Bound from {@code embedding.*}. */
    @Data
    @ConfigurationProperties(prefix = "embedding")
    public static class EmbeddingProperties {
        private String modelName = "default";
    }
}
