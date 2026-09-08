package org.hyland.contentlake.rag.observability;

import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.rag.config.HybridSearchProperties;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.conversation.ConversationMemoryService;
import org.hyland.contentlake.rag.conversation.SessionSummaryService;
import org.hyland.contentlake.rag.model.RagPromptRequest;
import org.hyland.contentlake.rag.model.SemanticSearchRequest;
import org.hyland.contentlake.rag.model.SemanticSearchResponse;
import org.hyland.contentlake.rag.service.CitationVerifier;
import org.hyland.contentlake.rag.service.ContentLakeRetrievalAdvisor;
import org.hyland.contentlake.rag.service.DiversitySelector;
import org.hyland.contentlake.rag.service.FilterInferenceService;
import org.hyland.contentlake.rag.service.HxprDocumentRetriever;
import org.hyland.contentlake.rag.service.HybridSearchService;
import org.hyland.contentlake.rag.service.NoOpRetrievalGrader;
import org.hyland.contentlake.rag.service.PromptInjectionScanner;
import org.hyland.contentlake.rag.service.QueryReformulationService;
import org.hyland.contentlake.rag.service.RagService;
import org.hyland.contentlake.rag.service.RerankService;
import org.hyland.contentlake.rag.service.SectionExpansionService;
import org.hyland.contentlake.rag.service.SemanticSearchService;
import org.hyland.contentlake.rag.service.StructuredAnswerService;
import org.hyland.contentlake.rag.service.StructuredLlmCaller;
import org.hyland.contentlake.security.SecurityContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Span structure and payload redaction, driven through the real advisor pipeline (#116).
 *
 * <h3>Why the redaction assertion is a whitelist</h3>
 * <p>{@link #withContentCaptureOff_noQueryOrChunkTextLeavesTheService()} collects <em>every</em> key
 * and value from <em>every</em> recorded span and asserts none contains a sentinel, rather than
 * checking a list of known content keys. A per-key blacklist passes forever; this fails the moment
 * someone adds a new content-bearing attribute without gating it.</p>
 *
 * <p>It is paired with a positive case that turns capture on and asserts each sentinel <em>is</em>
 * present. Without that pairing the negative test could pass vacuously, because the sentinels might
 * never have reached the pipeline at all. The pair is the whole value of the test.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagSpanPipelineTest {

    private static final String SENTINEL_QUESTION = "SENTINEL_QUESTION_7f3a";
    private static final String SENTINEL_CHUNK = "SENTINEL_CHUNK_9b21";
    private static final String SENTINEL_NAME = "SENTINEL_NAME_c4d8";
    private static final String SENTINEL_PATH = "/SENTINEL_PATH_e5f6/report.pdf";
    private static final String SENTINEL_ANSWER = "SENTINEL_ANSWER_a1b2";

    @Mock SemanticSearchService semanticSearchService;
    @Mock HybridSearchService hybridSearchService;
    @Mock ChatModel chatModel;
    @Mock ConversationMemoryService conversationMemoryService;
    @Mock QueryReformulationService queryReformulationService;
    @Mock RerankService rerankService;
    @Mock DiversitySelector diversitySelector;
    @Mock SecurityContextService securityContextService;
    @Mock FilterInferenceService filterInferenceService;
    @Mock SessionSummaryService sessionSummaryService;
    @Mock CitationVerifier citationVerifier;
    @Mock HxprDocumentApi hxprDocumentApi;

    @Test
    void producesOneTraceWhoseDescendantsIncludeRetrievalAugmentationAndGeneration() {
        RecordingObservations recorder = new RecordingObservations(true, false);
        RagService service = pipeline(recorder);

        service.prompt(request());

        // The shape the acceptance criterion asks for. Before #116 rag.generate *enclosed* retrieval,
        // because RagService wrapped the whole ChatClient call and the advisor's before-phase ran
        // inside it. This is the test that catches a regression to that shape.
        assertThat(recorder.names())
                .contains("rag.request", "rag.retrieve", "rag.augment", "rag.generate");
        // Ancestry, not direct parenthood: Spring AI's own advisor and model spans sit in between, and
        // Spring Boot's web instrumentation would add a server span above rag.request in a real
        // deployment. What the criterion is really about is that these are one connected trace.
        assertThat(recorder.ancestorNames("rag.retrieve")).contains("rag.request");
        assertThat(recorder.ancestorNames("rag.augment")).contains("rag.request");
        assertThat(recorder.ancestorNames("rag.generate")).contains("rag.request");
        assertThat(recorder.byName("rag.request").parentName()).isNull();
    }

    @Test
    void recordsChunkIdsScoresAndCountsWithoutContent() {
        RecordingObservations recorder = new RecordingObservations(true, false);

        pipeline(recorder).prompt(request());

        var retrieve = recorder.byName("rag.retrieve");
        assertThat(retrieve.high()).containsKeys(
                "rag.chunks.embedding_ids", "rag.chunks.document_ids",
                "rag.chunks.scores", "rag.chunks.ranks", "rag.retrieve.hits");
        assertThat(retrieve.high().get("rag.chunks.embedding_ids")).isEqualTo("emb-1");
        assertThat(retrieve.high().get("rag.retrieve.hits")).isEqualTo("1");

        var request = recorder.byName("rag.request");
        assertThat(request.low()).containsKeys("rag.path", "rag.model", "rag.features");
        assertThat(request.high()).containsKeys("rag.tokens.total", "rag.tokens.total.source",
                "rag.answer.length", "rag.request.id");
    }

    @Test
    void withContentCaptureOff_noQueryOrChunkTextLeavesTheService() {
        RecordingObservations recorder = new RecordingObservations(true, false);

        pipeline(recorder).prompt(request());

        // One assertion over every key and value of every span, including Spring AI's own.
        // Deliberately not a per-key check.
        assertThat(recorder.allText()).doesNotContain("SENTINEL_");
    }

    @Test
    void withContentCaptureOn_theSameContentIsRecorded() {
        // The companion to the test above. Without it, the negative assertion could pass simply
        // because the sentinels never reached the pipeline.
        RecordingObservations recorder = new RecordingObservations(true, true);

        pipeline(recorder).prompt(request());

        String all = recorder.allText();
        assertThat(all).contains(SENTINEL_QUESTION);
        assertThat(all).contains(SENTINEL_CHUNK);
        assertThat(all).contains(SENTINEL_ANSWER);
        // Source names and paths are gated too: a path like /HR/Terminations/2026/jsmith.pdf
        // discloses more than most chunk bodies.
        assertThat(all).contains(SENTINEL_NAME);
        assertThat(all).contains(SENTINEL_PATH);
    }

    @Test
    void withPayloadsOff_theSpansExistButCarryNothing() {
        RecordingObservations recorder = new RecordingObservations(false, false);

        pipeline(recorder).prompt(request());

        assertThat(recorder.names()).contains("rag.request", "rag.retrieve", "rag.generate");
        assertThat(recorder.ourText()).isEmpty();
    }

    @Test
    void withContentCaptureOn_contentIsStillTruncated() {
        RecordingObservations recorder = new RecordingObservations(true, true, 12, 20);

        pipeline(recorder).prompt(request());

        assertThat(recorder.byName("rag.request").high().get("rag.answer.text")).hasSize(12);
    }

    @Test
    void neverRecordsTheRawSessionId_evenWithContentCaptureOn() {
        // The session id is "user:<username>", so exporting it would put a username into a
        // third-party backend. A truncated hash still correlates a conversation's spans.
        RecordingObservations recorder = new RecordingObservations(true, true);

        pipeline(recorder).prompt(request());

        String all = recorder.allText();
        assertThat(all).doesNotContain("user:test-user");
        assertThat(recorder.byName("rag.request").high()).containsKey("rag.session.principal_hash");
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private static RagPromptRequest request() {
        RagPromptRequest request = new RagPromptRequest();
        request.setQuestion(SENTINEL_QUESTION);
        request.setSessionId("test-user");
        return request;
    }

    /**
     * The real pipeline: a real {@link ContentLakeRetrievalAdvisor} and {@link HxprDocumentRetriever}
     * over mocked search services and a mocked {@link ChatModel}, so the spans under assertion are the
     * ones production creates.
     */
    private RagService pipeline(RecordingObservations recorder) {
        RagProperties properties = new RagProperties();
        properties.setDefaultTopK(5);
        properties.setMaxContextLength(12000);
        properties.setDefaultSystemPrompt("system prompt");
        properties.setUseHybridSearch(false);
        properties.getObservability().setPayloadsEnabled(true);

        HybridSearchProperties hybridProperties = new HybridSearchProperties();

        // ChatClient request-building reads the model's options; the mock returns null by default.
        lenient().when(chatModel.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        lenient().when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse());
        lenient().when(securityContextService.getCurrentUsername()).thenReturn("test-user");
        lenient().when(semanticSearchService.search(any(SemanticSearchRequest.class)))
                .thenReturn(searchResponse());
        lenient().when(rerankService.rerank(any(), any())).thenAnswer(i -> i.getArgument(1));
        lenient().when(queryReformulationService.reformulate(any(), any()))
                .thenAnswer(i -> i.getArgument(0));

        HxprDocumentRetriever retriever =
                new HxprDocumentRetriever(semanticSearchService, hybridSearchService, properties);
        SectionExpansionService sectionExpansionService =
                new SectionExpansionService(hxprDocumentApi, properties);
        ContentLakeRetrievalAdvisor advisor = new ContentLakeRetrievalAdvisor(
                retriever, diversitySelector, rerankService, new NoOpRetrievalGrader(), properties,
                sectionExpansionService, new PromptInjectionScanner(),
                recorder.observations(), new RetrievalFeatureSet(properties, hybridProperties));

        // The registry must be handed to Spring AI, exactly as RagPipelineConfig does: without it the
        // ChatClient wraps the advisor chain in a noop-but-scope-handling observation that replaces the
        // current observation, and every advisor span becomes an unparented root.
        ChatClient chatClient = ChatClient
                .builder(chatModel, recorder.registry(), null, null)
                .defaultAdvisors(advisor)
                .build();

        return new RagService(
                chatClient,
                properties,
                conversationMemoryService,
                queryReformulationService,
                securityContextService,
                filterInferenceService,
                sessionSummaryService,
                citationVerifier,
                new StructuredAnswerService(new StructuredLlmCaller(chatModel)),
                null,
                recorder.observations(),
                new RetrievalFeatureSet(properties, hybridProperties));
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(SENTINEL_ANSWER))),
                ChatResponseMetadata.builder()
                        .model("test-model")
                        .usage(new DefaultUsage(10, 5, 15))
                        .build());
    }

    private static SemanticSearchResponse searchResponse() {
        SemanticSearchResponse.SourceDocument document = SemanticSearchResponse.SourceDocument.builder()
                .documentId("doc-1")
                .nodeId("node-1")
                .sourceType("alfresco")
                .name(SENTINEL_NAME)
                .path(SENTINEL_PATH)
                .mimeType("application/pdf")
                .build();

        SemanticSearchResponse.ChunkMetadata metadata = SemanticSearchResponse.ChunkMetadata.builder()
                .embeddingId("emb-1")
                .embeddingType("ai-mxbai-embed-large")
                .chunkLength(SENTINEL_CHUNK.length())
                .build();

        SemanticSearchResponse.SearchHit hit = SemanticSearchResponse.SearchHit.builder()
                .rank(1)
                .score(0.9)
                .chunkText(SENTINEL_CHUNK)
                .sourceDocument(document)
                .chunkMetadata(metadata)
                .build();

        return SemanticSearchResponse.builder().results(List.of(hit)).build();
    }
}
