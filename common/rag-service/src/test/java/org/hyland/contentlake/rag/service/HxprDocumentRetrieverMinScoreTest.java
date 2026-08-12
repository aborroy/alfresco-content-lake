package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.HybridSearchRequest;
import org.hyland.contentlake.rag.model.HybridSearchResponse;
import org.hyland.contentlake.rag.model.SemanticSearchRequest;
import org.hyland.contentlake.rag.model.SemanticSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * minScore must survive the trip to the search service on the hybrid path.
 *
 * <p>The retriever previously built the {@link HybridSearchRequest} without a minScore. Because
 * {@code rag.use-hybrid-search} defaults to true, that silently discarded both the request-level
 * value and {@code rag.default-min-score} in favour of the server-side hybrid default, on the path
 * that serves every RAG prompt. Any minScore setting, and any sweep over it, measured nothing.</p>
 */
@ExtendWith(MockitoExtension.class)
class HxprDocumentRetrieverMinScoreTest {

    @Mock
    private SemanticSearchService semanticSearchService;
    @Mock
    private HybridSearchService hybridSearchService;

    private RagProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
    }

    private HxprDocumentRetriever retriever() {
        return new HxprDocumentRetriever(semanticSearchService, hybridSearchService, properties);
    }

    private HybridSearchRequest captureHybridRequest(Map<String, Object> context) {
        when(hybridSearchService.search(any())).thenReturn(
                HybridSearchResponse.builder().results(List.of()).build());

        retriever().retrieve(Query.builder().text("a question").context(context).build());

        ArgumentCaptor<HybridSearchRequest> captor = ArgumentCaptor.forClass(HybridSearchRequest.class);
        verify(hybridSearchService).search(captor.capture());
        return captor.getValue();
    }

    @Test
    void hybridPathForwardsAnExplicitMinScore() {
        properties.setUseHybridSearch(true);

        HybridSearchRequest request = captureHybridRequest(
                Map.of(HxprDocumentRetriever.CTX_MIN_SCORE, 0.42));

        assertThat(request.getMinScore()).isEqualTo(0.42);
    }

    @Test
    void hybridPathForwardsAnExplicitZeroRatherThanTreatingItAsAbsent() {
        // 0.0 means "no threshold". It has to reach the service as 0.0, not as null, or the
        // server-side default silently reinstates a threshold the caller asked to remove.
        properties.setUseHybridSearch(true);

        HybridSearchRequest request = captureHybridRequest(
                Map.of(HxprDocumentRetriever.CTX_MIN_SCORE, 0.0));

        assertThat(request.getMinScore()).isEqualTo(0.0);
    }

    @Test
    void hybridPathFallsBackToTheConfiguredDefaultWhenTheContextIsSilent() {
        properties.setUseHybridSearch(true);
        properties.setDefaultMinScore(0.15);

        HybridSearchRequest request = captureHybridRequest(Map.of());

        assertThat(request.getMinScore()).isEqualTo(0.15);
    }

    @Test
    void semanticPathStillForwardsMinScore() {
        properties.setUseHybridSearch(false);
        when(semanticSearchService.search(any())).thenReturn(
                SemanticSearchResponse.builder().results(List.of()).build());

        retriever().retrieve(Query.builder()
                .text("a question")
                .context(Map.of(HxprDocumentRetriever.CTX_MIN_SCORE, 0.33))
                .build());

        ArgumentCaptor<SemanticSearchRequest> captor = ArgumentCaptor.forClass(SemanticSearchRequest.class);
        verify(semanticSearchService).search(captor.capture());
        assertThat(captor.getValue().getMinScore()).isEqualTo(0.33);
    }
}
