package org.hyland.filesystem.contentlake.batch.config;

import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.client.HxprQueryApi;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.config.HxprProperties;
import org.hyland.contentlake.extractor.TikaTextExtractor;
import org.hyland.contentlake.service.EmbeddingService;
import org.hyland.contentlake.service.GraphIngestionService;
import org.hyland.contentlake.service.NodeSyncService;
import org.hyland.contentlake.service.chunking.NoiseReductionService;
import org.hyland.contentlake.service.chunking.SimpleChunkingService;
import org.hyland.contentlake.service.chunking.strategy.ChunkingStrategy.ChunkingConfig;
import org.hyland.contentlake.spi.TextExtractor;
import org.hyland.filesystem.contentlake.client.FileSystemSourceClient;
import org.hyland.filesystem.contentlake.config.FileSystemProperties;
import org.hyland.filesystem.contentlake.service.FileSystemScopeResolver;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties({
        HxprProperties.class,
        FileSystemProperties.class,
        FilesystemBatchProperties.class
})
public class AppConfig {

    public static final String HXCS_REPOSITORY = "HXCS-REPOSITORY";

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
    public HxprService hxprService(HxprDocumentApi documentApi, HxprQueryApi queryApi, RestClient hxprRestClient) {
        return new HxprService(documentApi, queryApi, hxprRestClient);
    }

    @Bean
    public FileSystemSourceClient fileSystemSourceClient(FileSystemProperties props) {
        return new FileSystemSourceClient(props);
    }

    /** Source-agnostic extractor; yields to a source-specific one only if another is present. */
    @Bean
    @ConditionalOnMissingBean(TextExtractor.class)
    public TikaTextExtractor tikaTextExtractor() {
        return new TikaTextExtractor();
    }

    @Bean
    public FileSystemScopeResolver fileSystemScopeResolver(FileSystemProperties props) {
        return new FileSystemScopeResolver(props);
    }

    @Bean
    public EmbeddingService embeddingService(EmbeddingModel embeddingModel, FilesystemBatchProperties props) {
        return new EmbeddingService(embeddingModel, props.getEmbedding().getModelName());
    }

    @Bean
    public NoiseReductionService noiseReductionService(FilesystemBatchProperties props) {
        FilesystemBatchProperties.NoiseReduction cfg = props.getEmbedding().getNoiseReduction();
        return new NoiseReductionService(cfg.isEnabled(), cfg.isAggressive());
    }

    @Bean
    public ChunkingConfig chunkingConfig(FilesystemBatchProperties props) {
        FilesystemBatchProperties.Embedding embedding = props.getEmbedding();
        return new ChunkingConfig(
                embedding.getMinChunkSize(),
                embedding.getChunkSize(),
                embedding.getChunkOverlap(),
                embedding.getSimilarityThreshold());
    }

    @Bean
    public SimpleChunkingService chunkingService(NoiseReductionService noiseReductionService,
                                                 ChunkingConfig chunkingConfig) {
        return new SimpleChunkingService(noiseReductionService, chunkingConfig);
    }

    @Bean
    public NodeSyncService nodeSyncService(FileSystemSourceClient fileSystemSourceClient,
                                           HxprDocumentApi documentApi,
                                           HxprService hxprService,
                                           TikaTextExtractor tikaTextExtractor,
                                           EmbeddingService embeddingService,
                                           SimpleChunkingService chunkingService,
                                           HxprProperties props,
                                           @Value("${content-lake.ingest.keyword-context-enrichment-enabled:false}")
                                           boolean keywordContextEnrichmentEnabled,
                                           ObjectProvider<GraphIngestionService> graphIngestionServiceProvider) {
        return new NodeSyncService(
                fileSystemSourceClient,
                documentApi,
                hxprService,
                tikaTextExtractor,
                embeddingService,
                chunkingService,
                props.getTargetPath(),
                props.getPathRepositoryId(),
                keywordContextEnrichmentEnabled,
                graphIngestionServiceProvider.getIfAvailable());
    }

    @Bean(name = "filesystemBatchIngestionExecutor")
    public Executor filesystemBatchIngestionExecutor(FilesystemBatchProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getExecutor().getCoreSize());
        executor.setMaxPoolSize(props.getExecutor().getMaxSize());
        executor.setQueueCapacity(props.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("filesystem-batch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(props.getExecutor().getAwaitTerminationSeconds());
        executor.initialize();
        return executor;
    }

    private static ClientHttpRequestInterceptor hxprAuthInterceptor(HxprProperties props) {
        return (request, body, execution) -> {
            request.getHeaders().setBasicAuth(props.getUsername(), props.getPassword());
            request.getHeaders().set(HXCS_REPOSITORY, props.getRepositoryId());
            return execution.execute(request, body);
        };
    }

    private static HttpServiceProxyFactory httpProxyFactory(RestClient restClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
    }
}
