package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeiCrossEncoderRerankServiceTest {

    private static final String RERANKER_URL = "http://reranker:8081";

    @Mock RestTemplate restTemplate;

    private TeiCrossEncoderRerankService service;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        RagProperties.RerankerProperties rerankerProps = new RagProperties.RerankerProperties();
        rerankerProps.setUrl(RERANKER_URL);
        rerankerProps.setTopN(3);
        props.setReranker(rerankerProps);

        service = new TeiCrossEncoderRerankService(props);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    private static SearchHit hit(String text, double score) {
        return SearchHit.builder().chunkText(text).score(score).rank(0).build();
    }

    @Test
    void rerank_emptyHits_returnsEmptyList() {
        assertThat(service.rerank("query", List.of())).isEmpty();
        assertThat(service.rerank("query", null)).isEmpty();
        verifyNoInteractions(restTemplate);
    }

    @SuppressWarnings("unchecked")
    @Test
    void rerank_reordersHitsByTeiScore() {
        List<SearchHit> hits = List.of(
                hit("chunk A", 0.9),
                hit("chunk B", 0.8),
                hit("chunk C", 0.5)
        );

        // TEI says chunk B (index 1) is best, then C (index 2), then A (index 0)
        List<Map<String, Object>> teiResponse = List.of(
                Map.of("index", 0, "score", 0.20),
                Map.of("index", 1, "score", 0.95),
                Map.of("index", 2, "score", 0.60)
        );
        when(restTemplate.exchange(eq(RERANKER_URL + "/rerank"), eq(HttpMethod.POST),
                any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(teiResponse));

        List<SearchHit> result = service.rerank("query", hits);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getChunkText()).isEqualTo("chunk B");
        assertThat(result.get(0).getScore()).isEqualTo(0.95);
        assertThat(result.get(0).getRank()).isEqualTo(1);
        assertThat(result.get(1).getChunkText()).isEqualTo("chunk C");
        assertThat(result.get(2).getChunkText()).isEqualTo("chunk A");
    }

    @SuppressWarnings("unchecked")
    @Test
    void rerank_limitsResultsToTopN() {
        List<SearchHit> hits = List.of(
                hit("chunk A", 0.9),
                hit("chunk B", 0.8),
                hit("chunk C", 0.5),
                hit("chunk D", 0.4),
                hit("chunk E", 0.3)
        );

        List<Map<String, Object>> teiResponse = List.of(
                Map.of("index", 0, "score", 0.9),
                Map.of("index", 1, "score", 0.7),
                Map.of("index", 2, "score", 0.6),
                Map.of("index", 3, "score", 0.5),
                Map.of("index", 4, "score", 0.4)
        );
        when(restTemplate.exchange(any(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(teiResponse));

        List<SearchHit> result = service.rerank("query", hits);

        // topN = 3 in setUp
        assertThat(result).hasSize(3);
    }

    @SuppressWarnings("unchecked")
    @Test
    void rerank_sendsCorrectRequestBody() {
        List<SearchHit> hits = List.of(hit("hello world", 0.8), hit("foo bar", 0.5));
        when(restTemplate.exchange(any(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(List.of(
                        Map.of("index", 0, "score", 0.9),
                        Map.of("index", 1, "score", 0.3))));

        service.rerank("my query", hits);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.captor();
        verify(restTemplate).exchange(eq(RERANKER_URL + "/rerank"), eq(HttpMethod.POST),
                captor.capture(), any(Class.class));

        Map<String, Object> body = captor.getValue().getBody();
        assertThat(body).containsEntry("query", "my query");
        assertThat(body).containsEntry("truncate", true);
        assertThat(body.get("texts")).isEqualTo(List.of("hello world", "foo bar"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void rerank_teiReturnsError_fallsBackToOriginalOrder() {
        List<SearchHit> hits = List.of(hit("chunk A", 0.9), hit("chunk B", 0.5));
        when(restTemplate.exchange(any(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new RuntimeException("TEI unavailable"));

        List<SearchHit> result = service.rerank("query", hits);

        // Falls back to original order, capped at topN
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getChunkText()).isEqualTo("chunk A");
        assertThat(result.get(1).getChunkText()).isEqualTo("chunk B");
    }

    @SuppressWarnings("unchecked")
    @Test
    void rerank_teiReturnsEmptyBody_fallsBackToOriginalOrder() {
        List<SearchHit> hits = List.of(hit("chunk A", 0.9), hit("chunk B", 0.5));
        when(restTemplate.exchange(any(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(List.of()));

        List<SearchHit> result = service.rerank("query", hits);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getChunkText()).isEqualTo("chunk A");
    }
}
