package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.ChunkMetadata;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SourceDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LlmRerankServiceTest {

    private ChatModel chatModel;
    private RagProperties properties;
    private LlmRerankService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        properties = new RagProperties();
        properties.getReranker().setTopN(8);
        service = new LlmRerankService(chatModel, properties);
    }

    private static SearchHit hit(String text, double score) {
        return SearchHit.builder()
                .score(score)
                .chunkText(text)
                .sourceDocument(SourceDocument.builder().documentId("d").build())
                .chunkMetadata(ChunkMetadata.builder().embeddingId("e").build())
                .build();
    }

    private void stubLlmResponse(String text) {
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn(text);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    @Test
    void rerank_emptyHits_returnsEmpty() {
        assertThat(service.rerank("q", List.of())).isEmpty();
        assertThat(service.rerank("q", null)).isEmpty();
        verifyNoInteractions(chatModel);
    }

    @Test
    void rerank_validScores_reordersByLlmRating() {
        // hit 0 rated 2, hit 1 rated 5 -> hit 1 should come first
        stubLlmResponse("0=2\n1=5");

        List<SearchHit> result = service.rerank("q", List.of(hit("first", 0.9), hit("second", 0.4)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getChunkText()).isEqualTo("second");
        assertThat(result.get(1).getChunkText()).isEqualTo("first");
        assertThat(result.get(0).getRank()).isEqualTo(1);
        assertThat(result.get(1).getRank()).isEqualTo(2);
    }

    @Test
    void rerank_keepsTopN() {
        properties.getReranker().setTopN(1);
        stubLlmResponse("0=2\n1=5");

        List<SearchHit> result = service.rerank("q", List.of(hit("first", 0.9), hit("second", 0.4)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getChunkText()).isEqualTo("second");
    }

    @Test
    void rerank_preservesOriginalCosineScoreAndMetadata() {
        stubLlmResponse("0=5\n1=1");

        SearchHit original = SearchHit.builder()
                .score(0.87)
                .chunkText("keep me")
                .sourceDocument(SourceDocument.builder().documentId("doc-1").build())
                .chunkMetadata(ChunkMetadata.builder().embeddingId("emb-1").page(3).build())
                .vector(List.of(0.1, 0.2))
                .build();

        List<SearchHit> result = service.rerank("q", List.of(original, hit("other", 0.5)));

        SearchHit top = result.get(0);
        assertThat(top.getScore()).isEqualTo(0.87);           // cosine score preserved, not 1-5
        assertThat(top.getSourceDocument().getDocumentId()).isEqualTo("doc-1");
        assertThat(top.getChunkMetadata().getPage()).isEqualTo(3);
        assertThat(top.getVector()).containsExactly(0.1, 0.2);
    }

    @Test
    void rerank_llmThrows_fallsBackToInputOrder() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("model down"));

        List<SearchHit> input = List.of(hit("a", 0.9), hit("b", 0.4));
        List<SearchHit> result = service.rerank("q", input);

        assertThat(result).extracting(SearchHit::getChunkText).containsExactly("a", "b");
    }

    @Test
    void rerank_unparseableResponse_keepsInputOrder() {
        stubLlmResponse("I cannot score these passages.");

        List<SearchHit> result = service.rerank("q", List.of(hit("a", 0.9), hit("b", 0.4)));

        assertThat(result).extracting(SearchHit::getChunkText).containsExactly("a", "b");
    }
}
