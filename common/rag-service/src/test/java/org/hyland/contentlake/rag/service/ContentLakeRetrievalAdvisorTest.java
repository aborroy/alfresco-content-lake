package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the optional stages are composed correctly inside the advisor pipeline:
 * over-retrieve -> MMR diversify -> rerank -> relevance gate, each only when its flag is on.
 */
class ContentLakeRetrievalAdvisorTest {

    private DocumentRetriever documentRetriever;
    private DiversitySelector diversitySelector;
    private RerankService rerankService;
    private RetrievalGrader retrievalGrader;
    private RagProperties properties;
    private ContentLakeRetrievalAdvisor advisor;

    @BeforeEach
    void setUp() {
        documentRetriever = mock(DocumentRetriever.class);
        diversitySelector = mock(DiversitySelector.class);
        rerankService = mock(RerankService.class);
        retrievalGrader = mock(RetrievalGrader.class);
        properties = new RagProperties();
        properties.setDefaultTopK(2);
        advisor = new ContentLakeRetrievalAdvisor(
                documentRetriever, diversitySelector, rerankService, retrievalGrader, properties);
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

    // ------------------------------------------------------------------
    // Self-RAG relevance gate
    // ------------------------------------------------------------------

    @Test
    void adviseCall_gradingDisabled_neverConsultsTheGrader() {
        List<Document> pool = List.of(doc(hit("a")));
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(pool);
        when(rerankService.rerank(any(), anyList())).thenAnswer(inv -> inv.getArgument(1));

        advisor.adviseCall(request(), chainReturning());

        verifyNoInteractions(retrievalGrader);
    }

    @Test
    void adviseCall_gradedRelevant_generatesFromTheFirstPass() {
        properties.getRetrievalGrading().setEnabled(true);

        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(doc(hit("a"))));
        when(rerankService.rerank(any(), anyList())).thenAnswer(inv -> inv.getArgument(1));
        when(retrievalGrader.grade(any(), anyList())).thenReturn(RetrievalGrader.Verdict.RELEVANT);

        CallAdvisorChain chain = chainReturning();
        advisor.adviseCall(request(), chain);

        // One retrieval, no broadened retry, and the chain was invoked.
        verify(documentRetriever, times(1)).retrieve(any(Query.class));
        verify(chain).nextCall(any());
    }

    @Test
    void adviseCall_gradedWeakThenRelevant_retriesOnceWithBroadenedParams() {
        properties.getRetrievalGrading().setEnabled(true);
        properties.getMmr().setPoolSize(30);

        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(doc(hit("a"))));
        when(rerankService.rerank(any(), anyList())).thenAnswer(inv -> inv.getArgument(1));
        when(retrievalGrader.grade(any(), anyList()))
                .thenReturn(RetrievalGrader.Verdict.WEAK)
                .thenReturn(RetrievalGrader.Verdict.RELEVANT);

        CallAdvisorChain chain = chainReturning();
        advisor.adviseCall(request(), chain);

        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        verify(documentRetriever, times(2)).retrieve(queries.capture());
        assertThat(queries.getAllValues()).hasSize(2);

        // The retry drops the threshold and widens the pool; the first pass is untouched.
        Map<String, Object> first = queries.getAllValues().get(0).context();
        Map<String, Object> second = queries.getAllValues().get(1).context();
        assertThat(first.get(HxprDocumentRetriever.CTX_TOP_K)).isEqualTo(2);
        assertThat(second.get(HxprDocumentRetriever.CTX_MIN_SCORE)).isEqualTo(0.0d);
        assertThat(second.get(HxprDocumentRetriever.CTX_TOP_K)).isEqualTo(30);

        verify(chain).nextCall(any());
    }

    @Test
    void adviseCall_persistentlyWeak_skipsGenerationEntirely() {
        properties.getRetrievalGrading().setEnabled(true);

        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(doc(hit("a"))));
        when(rerankService.rerank(any(), anyList())).thenAnswer(inv -> inv.getArgument(1));
        when(retrievalGrader.grade(any(), anyList())).thenReturn(RetrievalGrader.Verdict.WEAK);

        CallAdvisorChain chain = chainReturning();
        ChatClientResponse response = advisor.adviseCall(request(), chain);

        // Bounded at one retry, then the empty-context short-circuit takes over: no LLM call.
        verify(documentRetriever, times(2)).retrieve(any(Query.class));
        verify(chain, never()).nextCall(any());
        assertThat(response.chatResponse().getResult().getOutput().getText())
                .isEqualTo(ContentLakeRetrievalAdvisor.NO_CONTEXT_ANSWER);
    }

    @Test
    void adviseCall_weakWithBroadeningOff_skipsGenerationWithoutRetrying() {
        properties.getRetrievalGrading().setEnabled(true);
        properties.getRetrievalGrading().setBroaden(false);

        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(doc(hit("a"))));
        when(rerankService.rerank(any(), anyList())).thenAnswer(inv -> inv.getArgument(1));
        when(retrievalGrader.grade(any(), anyList())).thenReturn(RetrievalGrader.Verdict.WEAK);

        CallAdvisorChain chain = chainReturning();
        advisor.adviseCall(request(), chain);

        verify(documentRetriever, times(1)).retrieve(any(Query.class));
        verify(chain, never()).nextCall(any());
    }
}
