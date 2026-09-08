package org.hyland.alfresco.contentlake.batch.config;

import org.hyland.alfresco.contentlake.client.AlfrescoClient;
import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.client.HxprQueryApi;
import org.hyland.contentlake.client.HxprService;
import org.hyland.alfresco.contentlake.client.TransformClient;
import org.hyland.contentlake.config.HxprProperties;
import org.hyland.alfresco.contentlake.config.TransformProperties;
import org.hyland.alfresco.contentlake.service.ContentLakeScopeResolver;
import org.hyland.contentlake.service.Chunker;
import org.hyland.contentlake.service.EmbeddingService;
import org.hyland.contentlake.service.EmbeddingTypeResolver;
import org.hyland.contentlake.service.IndexProofService;
import org.hyland.contentlake.service.IndexReconciliationService;
import org.hyland.contentlake.service.NodeSyncService;
import org.hyland.contentlake.service.chunking.*;
import org.hyland.contentlake.service.chunking.strategy.ChunkingStrategy.ChunkingConfig;
import org.springframework.ai.embedding.EmbeddingModel;
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
 * Central Spring configuration for batch-ingester infrastructure.
 */
@Configuration
@EnableConfigurationProperties({
        IngestionProperties.class,
        HxprProperties.class,
        TransformProperties.class
})
public class AppConfig {

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

    @Bean
    public HxprService hxprService(HxprDocumentApi documentApi,
                                   HxprQueryApi queryApi,
                                   RestClient hxprRestClient,
                                   IngestionProperties props) {
        return new HxprService(documentApi, queryApi, hxprRestClient,
                EmbeddingTypeResolver.toEmbeddingType(props.getEmbedding().getModelName()));
    }

    // ----------------------------------------------------------------------
    // Transform Service
    // ----------------------------------------------------------------------

    @Bean
    public TransformClient transformClient(TransformProperties props) {
        return new TransformClient(props.getUrl(), props.getTimeoutMs());
    }

    // ----------------------------------------------------------------------
    // Embedding pipeline
    // ----------------------------------------------------------------------

    @Bean
    public Chunker chunker(IngestionProperties props) {
        return new Chunker(
                props.getEmbedding().getChunkSize(),
                props.getEmbedding().getChunkOverlap()
        );
    }

    @Bean
    public EmbeddingService embeddingService(EmbeddingModel embeddingModel, IngestionProperties props) {
        return new EmbeddingService(embeddingModel, props.getEmbedding().getModelName());
    }

    @Bean
    public IndexProofService indexProofService(HxprService hxprService, EmbeddingService embeddingService) {
        return new IndexProofService(hxprService, embeddingService);
    }

    @Bean
    public IndexReconciliationService indexReconciliationService(HxprService hxprService,
                                                                NodeSyncService nodeSyncService,
                                                                AlfrescoClient alfrescoClient) {
        return new IndexReconciliationService(hxprService, nodeSyncService, alfrescoClient);
    }

    // ----------------------------------------------------------------------
    // Chunking pipeline
    // ----------------------------------------------------------------------

    @Bean
    public NoiseReductionService noiseReductionService(IngestionProperties props) {
        return new NoiseReductionService(
                props.getEmbedding().getNoiseReduction().isAggressive()
        );
    }

    @Bean
    public ChunkingConfig chunkingConfig(IngestionProperties props) {
        IngestionProperties.Embedding emb = props.getEmbedding();
        return new ChunkingConfig(
                emb.getMinChunkSize(),
                emb.getChunkSize(),
                emb.getChunkOverlap(),
                emb.getSimilarityThreshold()
        );
    }

    @Bean
    public SimpleChunkingService chunkingService(
            NoiseReductionService noiseReduction,
            ChunkingConfig config) {
        return new SimpleChunkingService(noiseReduction, config);
    }

    @Bean(name = "statusLookupExecutor")
    public Executor statusLookupExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public ContentLakeScopeResolver contentLakeScopeResolver(IngestionProperties props,
                                                              AlfrescoClient alfrescoClient) {
        return new ContentLakeScopeResolver(
                props.getExclude().getPaths(),
                props.getExclude().getAspects(),
                alfrescoClient
        );
    }

    @Bean
    public NodeSyncService nodeSyncService(
            AlfrescoClient alfrescoClient,
            HxprDocumentApi documentApi,
            HxprService hxprService,
            TransformClient transformClient,
            EmbeddingService embeddingService,
            SimpleChunkingService chunkingService,
            HxprProperties props,
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
                props.getTargetPath(),
                props.getPathRepositoryId(),
                keywordContextEnrichmentEnabled
        );
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    // Package-visible for unit testing (see AppConfigTest). Basic auth against the ai-ready-index engine.
    static ClientHttpRequestInterceptor hxprAuthInterceptor(HxprProperties props) {
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
}
