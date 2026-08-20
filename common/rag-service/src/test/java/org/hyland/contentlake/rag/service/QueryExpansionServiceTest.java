package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.service.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueryExpansionServiceTest {

    private ChatModel chatModel;
    private EmbeddingService embeddingService;
    private RagProperties properties;
    private QueryExpansionService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        embeddingService = mock(EmbeddingService.class);
        properties = new RagProperties();
        service = new QueryExpansionService(chatModel, embeddingService, properties);
    }

    private void respondWith(String text) {
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn(text);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    // -----------------------------------------------------------------------
    // Disabled by default
    // -----------------------------------------------------------------------

    @Test
    void expand_everythingDisabled_returnsNullWithoutCallingTheModel() {
        assertThat(service.expand("who approved the change?")).isNull();
        verifyNoInteractions(chatModel, embeddingService);
    }

    @Test
    void expand_blankQuery_returnsNull() {
        properties.getMultiQuery().setEnabled(true);

        assertThat(service.expand("  ")).isNull();
        assertThat(service.expand(null)).isNull();
        verifyNoInteractions(chatModel);
    }

    // -----------------------------------------------------------------------
    // Multi-query
    // -----------------------------------------------------------------------

    @Test
    void expand_multiQuery_appendsParsedVariantsAfterTheOriginal() {
        properties.getMultiQuery().setEnabled(true);
        properties.getMultiQuery().setVariants(3);
        respondWith("""
                1. who signed off on the change
                - change approval authority
                * CAB approval record
                """);

        List<QueryVariant> variants = service.expand("who approved the change?");

        assertThat(variants).hasSize(4);
        assertThat(variants.get(0).label()).isEqualTo(QueryVariant.LABEL_ORIGINAL);
        assertThat(variants.get(0).vectorText()).isEqualTo("who approved the change?");
        // List decoration is stripped and both legs get the same text.
        assertThat(variants).extracting(QueryVariant::vectorText)
                .containsExactly("who approved the change?",
                        "who signed off on the change",
                        "change approval authority",
                        "CAB approval record");
        assertThat(variants).allMatch(QueryVariant::hasKeywordLeg);
        assertThat(variants).allMatch(v -> v.vectorVector() == null);
    }

    @Test
    void expand_multiQuery_honoursTheRequestedVariantCount() {
        properties.getMultiQuery().setEnabled(true);
        properties.getMultiQuery().setVariants(1);
        respondWith("first alternative\nsecond alternative\nthird alternative");

        assertThat(service.expand("q")).hasSize(2);
    }

    @Test
    void expand_multiQuery_dropsVariantsThatRepeatTheOriginalOrEachOther() {
        properties.getMultiQuery().setEnabled(true);
        respondWith("Q\nsomething else\nSOMETHING ELSE");

        List<QueryVariant> variants = service.expand("q");

        assertThat(variants).extracting(QueryVariant::vectorText).containsExactly("q", "something else");
    }

    @Test
    void expand_modelThrows_yieldsTheOriginalOnly() {
        properties.getMultiQuery().setEnabled(true);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("model down"));

        List<QueryVariant> variants = service.expand("q");

        assertThat(variants).hasSize(1);
        assertThat(variants.get(0).label()).isEqualTo(QueryVariant.LABEL_ORIGINAL);
    }

    @Test
    void expand_modelReturnsBlank_yieldsTheOriginalOnly() {
        properties.getMultiQuery().setEnabled(true);
        respondWith("   ");

        assertThat(service.expand("q")).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // HyDE
    // -----------------------------------------------------------------------

    @Test
    void expand_hyde_embedsDocumentSideAndSkipsTheKeywordLeg() {
        properties.getHyde().setEnabled(true);
        respondWith("Client credentials are exchanged at the token endpoint for a bearer token.");
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3));

        List<QueryVariant> variants = service.expand("how does a machine caller authenticate?");

        assertThat(variants).hasSize(2);
        QueryVariant hyde = variants.get(1);
        assertThat(hyde.label()).isEqualTo("hyde");
        assertThat(hyde.vectorVector()).containsExactly(0.1, 0.2, 0.3);
        // The hypothetical passage must never reach the keyword leg: its incidental vocabulary would
        // displace the terms the user actually asked about.
        assertThat(hyde.hasKeywordLeg()).isFalse();
        assertThat(hyde.keywordText()).isNull();

        // Document-side embedding, i.e. no query instruction prefix.
        verify(embeddingService).embed(
                "Client credentials are exchanged at the token endpoint for a bearer token.");
        verify(embeddingService, never()).embedQuery(anyString());
    }

    @Test
    void expand_hyde_truncatesThePassageToMaxChars() {
        properties.getHyde().setEnabled(true);
        properties.getHyde().setMaxChars(10);
        respondWith("0123456789abcdefghij");
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1));

        service.expand("q");

        verify(embeddingService).embed("0123456789");
    }

    @Test
    void expand_hyde_emptyEmbedding_dropsTheVariant() {
        properties.getHyde().setEnabled(true);
        respondWith("a hypothetical passage");
        when(embeddingService.embed(anyString())).thenReturn(List.of());

        assertThat(service.expand("q")).hasSize(1);
    }

    @Test
    void expand_hyde_embeddingThrows_dropsTheVariant() {
        properties.getHyde().setEnabled(true);
        respondWith("a hypothetical passage");
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("embedder down"));

        assertThat(service.expand("q")).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Decomposition
    // -----------------------------------------------------------------------

    @Test
    void expand_decomposition_appendsSubQuestions() {
        properties.getQueryDecomposition().setEnabled(true);
        respondWith("""
                What was Q3 revenue?
                What was Q4 revenue?
                Who approved each figure?
                """);

        List<QueryVariant> variants = service.expand("compare Q3 and Q4 revenue and say who approved each");

        assertThat(variants).hasSize(4);
        assertThat(variants).extracting(QueryVariant::label)
                .containsExactly(QueryVariant.LABEL_ORIGINAL, "sub-1", "sub-2", "sub-3");
        assertThat(variants).allMatch(QueryVariant::hasKeywordLeg);
    }

    @Test
    void expand_decomposition_notCompound_yieldsTheOriginalOnly() {
        properties.getQueryDecomposition().setEnabled(true);
        respondWith("NONE");

        assertThat(service.expand("what is the token lifetime?")).hasSize(1);
    }

    @Test
    void expand_decomposition_honoursMaxSubQuestions() {
        properties.getQueryDecomposition().setEnabled(true);
        properties.getQueryDecomposition().setMaxSubQuestions(2);
        respondWith("first?\nsecond?\nthird?\nfourth?");

        assertThat(service.expand("q")).hasSize(3);
    }

    // -----------------------------------------------------------------------
    // Shared ceiling
    // -----------------------------------------------------------------------

    @Test
    void expand_capsTotalVariantsAtMaxVariants() {
        properties.getQueryExpansion().setMaxVariants(3);
        properties.getMultiQuery().setEnabled(true);
        properties.getMultiQuery().setVariants(5);
        respondWith("one\ntwo\nthree\nfour\nfive");

        List<QueryVariant> variants = service.expand("q");

        assertThat(variants).hasSize(3);
        // The original always survives the truncation.
        assertThat(variants.get(0).label()).isEqualTo(QueryVariant.LABEL_ORIGINAL);
    }

    @Test
    void expand_allThreeEnabled_ordersDecompositionThenHydeThenParaphrases() {
        properties.getQueryExpansion().setMaxVariants(10);
        properties.getQueryDecomposition().setEnabled(true);
        properties.getHyde().setEnabled(true);
        properties.getMultiQuery().setEnabled(true);
        properties.getMultiQuery().setVariants(1);
        // One stubbed response serves all three calls; each parses it in its own way.
        respondWith("alpha");
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1));

        List<QueryVariant> variants = service.expand("q");

        // "alpha" becomes sub-1; the paraphrase leg then sees it as a duplicate and adds nothing.
        assertThat(variants).extracting(QueryVariant::label)
                .containsExactly(QueryVariant.LABEL_ORIGINAL, "sub-1", "hyde");
    }
}
