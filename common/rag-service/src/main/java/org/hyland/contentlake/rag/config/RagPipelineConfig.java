package org.hyland.contentlake.rag.config;

import org.hyland.contentlake.rag.service.ContentLakeRetrievalAdvisor;
import org.hyland.contentlake.rag.service.HxprDocumentRetriever;
import org.hyland.contentlake.rag.service.HybridSearchService;
import org.hyland.contentlake.rag.service.RerankService;
import org.hyland.contentlake.rag.service.SemanticSearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Spring AI RAG advisor pipeline: a {@link DocumentRetriever} over the hxpr
 * search stack, the composable {@link ContentLakeRetrievalAdvisor}, and the single
 * {@link ChatClient} used by both the synchronous and streaming RAG paths.
 *
 * <p>Kept separate from {@link RagAppConfig} (hxpr/embedding infra) so slices that load
 * only the infrastructure config are not forced to satisfy the search-service and
 * chat-model beans this pipeline depends on.</p>
 */
@Configuration
public class RagPipelineConfig {

    @Bean
    public DocumentRetriever hxprDocumentRetriever(SemanticSearchService semanticSearchService,
                                                   HybridSearchService hybridSearchService,
                                                   RagProperties ragProperties) {
        return new HxprDocumentRetriever(semanticSearchService, hybridSearchService, ragProperties);
    }

    @Bean
    public ContentLakeRetrievalAdvisor contentLakeRetrievalAdvisor(DocumentRetriever hxprDocumentRetriever,
                                                                   RerankService rerankService,
                                                                   RagProperties ragProperties) {
        return new ContentLakeRetrievalAdvisor(hxprDocumentRetriever, rerankService, ragProperties);
    }

    /**
     * Single {@link ChatClient} used by both the synchronous and streaming RAG paths.
     * The retrieval/rerank/augment pipeline is composed as a default advisor, so
     * {@code RagService} no longer branches between {@code chatModel.call()} and
     * {@code ChatClient.stream()} with hand-wired retrieval.
     */
    @Bean
    public ChatClient ragChatClient(ChatModel chatModel, ContentLakeRetrievalAdvisor contentLakeRetrievalAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(contentLakeRetrievalAdvisor)
                .build();
    }
}
