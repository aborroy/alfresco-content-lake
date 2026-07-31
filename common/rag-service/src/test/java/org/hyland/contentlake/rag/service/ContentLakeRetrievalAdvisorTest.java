package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the MMR diversity step is composed correctly inside the advisor pipeline:
 * over-retrieve -> MMR diversify -> rerank, only when {@code rag.mmr.enabled}.
 */
class ContentLakeRetrievalAdvisorTest {

    private DocumentRetriever documentRetriever;
    private DiversitySelector diversitySelector;
    private RerankService rerankService;
    private RagProperties properties;
    private ContentLakeRetrievalAdvisor advisor;

    @BeforeEach
    void setUp() {
        documentRetriever = mock(DocumentRetriever.class);
        diversitySelector = mock(DiversitySelector.class);
        rerankService = mock(RerankService.class);
        properties = new RagProperties();
        properties.setDefaultTopK(2);
        advisor = new ContentLakeRetrievalAdvisor(documentRetriever, diversitySelector, rerankService, properties);
    }

    private static SearchHit hit(String text) {
        return SearchHit.builder().chunkText(text).score(0.5).build();
    }

    private static Document doc(SearchHit hit) {
        return Document.builder()
                .text(hit.getChunkText())
                .score(hit.getScore())
                .metadata(HxprDocumentRetriever.HIT_METADATA_KEY, hit)
                .build();
    }

    private ChatClientRequest request() {
        Prompt prompt = new Prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("q")));
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(HxprDocumentRetriever.CTX_TOP_K, 2))
                .build();
    }

    private static CallAdvisorChain chainReturning() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatResponse resp = new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        when(chain.nextCall(any())).thenReturn(
                ChatClientResponse.builder().chatResponse(resp).context(Map.of()).build());
        return chain;
    }

    @Test
    void adviseCall_mmrEnabled_diversifiesBetweenRetrieveAndRerank() {
        properties.getMmr().setEnabled(true);

        List<Document> pool = List.of(doc(hit("a")), doc(hit("b")), doc(hit("c")));
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(pool);

        List<SearchHit> diversified = List.of(hit("a"), hit("c"));
        when(diversitySelector.select(anyList(), eq(2))).thenReturn(diversified);
        when(rerankService.rerank(any(), eq(diversified))).thenReturn(diversified);

        advisor.adviseCall(request(), chainReturning());

        // MMR ran with the full pool and topK, then rerank saw exactly the diversified shortlist.
        verify(diversitySelector).select(anyList(), eq(2));
        verify(rerankService).rerank(any(), eq(diversified));
    }

    @Test
    void adviseCall_mmrDisabled_skipsDiversitySelector() {
        properties.getMmr().setEnabled(false);

        List<Document> pool = List.of(doc(hit("a")), doc(hit("b")));
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(pool);
        when(rerankService.rerank(any(), anyList())).thenAnswer(inv -> inv.getArgument(1));

        advisor.adviseCall(request(), chainReturning());

        verify(diversitySelector, never()).select(anyList(), anyInt());
        verify(rerankService).rerank(any(), anyList());
    }
}
