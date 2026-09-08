package org.hyland.contentlake.rag.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * The streaming-latency criterion of #116: span enrichment must not sit between the last streamed
 * token and the {@code metadata} SSE event.
 *
 * <p>Asserted as an <strong>ordering</strong>, not a timing threshold. The client has the whole answer
 * on screen but cannot render its sources until {@code metadata} arrives, so anything slow before that
 * send reads as a stall. A wall-clock assertion would be flaky and would also pass for a payload that
 * merely happens to be small today; asserting that the enrichment is recorded after the send makes the
 * guarantee structural.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagStreamSpanOrderingTest {

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

    /** Ordered log of what happened, appended to by the fake emitter and the recording handler. */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    /**
     * Released when the emitter completes. Spring AI's stream does not necessarily finish before
     * {@code streamPrompt} returns, so asserting immediately would race it -- and because AssertJ
     * renders its failure message from the live list, such a race reads as a nonsensical "could not
     * find an element that is plainly there".
     */
    private final CountDownLatch completed = new CountDownLatch(1);

    private void awaitCompletion() throws InterruptedException {
        assertThat(completed.await(10, TimeUnit.SECONDS))
                .as("the SSE stream should have completed").isTrue();
    }

    @Test
    void theMetadataEventIsSentBeforeTheRequestSpanIsEnriched() throws Exception {
        RagService service = pipeline();

        RagPromptRequest request = new RagPromptRequest();
        request.setQuestion("what is in the report");
        request.setSessionId("test-user");

        service.streamPrompt(request);
        awaitCompletion();

        assertThat(events).contains("metadata", "span-enriched");
        assertThat(events.indexOf("metadata")).isLessThan(events.indexOf("span-enriched"));
    }

    @Test
    void theStreamStillProducesARequestSpan() throws Exception {
        pipeline().streamPrompt(streamRequest());
        awaitCompletion();

        assertThat(events).contains("span-stopped:rag.request");
    }

    @Test
    void theSpanIsStoppedEvenThoughTheStreamCompletesAsynchronouslyOfTheMethodReturn() throws Exception {
        pipeline().streamPrompt(streamRequest());
        awaitCompletion();

        // Exactly once, despite the completion callback, onCompletion and onTimeout all closing it.
        long stops = events.stream().filter("span-stopped:rag.request"::equals).count();
        assertThat(stops).isEqualTo(1);
    }

    private static RagPromptRequest streamRequest() {
        RagPromptRequest request = new RagPromptRequest();
        request.setQuestion("what is in the report");
        request.setSessionId("test-user");
        return request;
    }

    private RagService pipeline() {
        RagProperties properties = new RagProperties();
        properties.setDefaultTopK(5);
        properties.setMaxContextLength(12000);
        properties.setDefaultSystemPrompt("system prompt");
        properties.setUseHybridSearch(false);
        properties.getObservability().setPayloadsEnabled(true);

        HybridSearchProperties hybridProperties = new HybridSearchProperties();

        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new EventRecordingHandler());

        RagObservations observations = new RagObservations(provider(registry), provider(properties));

        lenient().when(chatModel.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        lenient().when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse()));
        lenient().when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse());
        lenient().when(securityContextService.getCurrentUsername()).thenReturn("test-user");
        lenient().when(semanticSearchService.search(any(SemanticSearchRequest.class)))
                .thenReturn(searchResponse());
        lenient().when(rerankService.rerank(any(), any())).thenAnswer(i -> i.getArgument(1));
        lenient().when(queryReformulationService.reformulate(any(), any()))
                .thenAnswer(i -> i.getArgument(0));

        HxprDocumentRetriever retriever =
                new HxprDocumentRetriever(semanticSearchService, hybridSearchService, properties);
        ContentLakeRetrievalAdvisor advisor = new ContentLakeRetrievalAdvisor(
                retriever, diversitySelector, rerankService, new NoOpRetrievalGrader(), properties,
                new SectionExpansionService(hxprDocumentApi, properties), new PromptInjectionScanner(),
                observations, new RetrievalFeatureSet(properties, hybridProperties));

        ChatClient chatClient = ChatClient.builder(chatModel, registry, null, null)
                .defaultAdvisors(advisor)
                .build();

        return new RagServiceWithRecordingEmitter(
                chatClient, properties, conversationMemoryService, queryReformulationService,
                securityContextService, filterInferenceService, sessionSummaryService, citationVerifier,
                new StructuredAnswerService(new StructuredLlmCaller(chatModel)), null,
                observations, new RetrievalFeatureSet(properties, hybridProperties));
    }

    /**
     * A {@link RagService} whose SSE emitter records each event name in order, so the test can assert
     * that {@code metadata} is sent before the span is enriched.
     */
    private final class RagServiceWithRecordingEmitter extends RagService {

        private RagServiceWithRecordingEmitter(ChatClient chatClient, RagProperties properties,
                ConversationMemoryService conversationMemoryService,
                QueryReformulationService queryReformulationService,
                SecurityContextService securityContextService,
                FilterInferenceService filterInferenceService,
                SessionSummaryService sessionSummaryService, CitationVerifier citationVerifier,
                StructuredAnswerService structuredAnswerService, Object unusedToolset,
                RagObservations observations, RetrievalFeatureSet retrievalFeatures) {
            super(chatClient, properties, conversationMemoryService, queryReformulationService,
                    securityContextService, filterInferenceService, sessionSummaryService,
                    citationVerifier, structuredAnswerService, null, observations, retrievalFeatures);
        }

        @Override
        protected SseEmitter newEmitter() {
            return new SseEmitter(0L) {
                @Override
                public void send(SseEventBuilder builder) throws IOException {
                    // The event name is not exposed, so record the send order by position: the first
                    // non-token send in this pipeline is metadata.
                    events.add("metadata");
                    // Deliberately not delegating: a real send needs an active async request.
                }

                @Override
                public void send(Object object) throws IOException {
                    events.add("raw-send");
                }

                @Override
                public void complete() {
                    events.add("complete");
                    completed.countDown();
                }
            };
        }
    }

    /** Records span stops and payload attachment, so ordering against the SSE send is observable. */
    private final class EventRecordingHandler implements ObservationHandler<Observation.Context> {

        @Override
        public void onStop(Observation.Context context) {
            if (context.getName().startsWith("rag.")) {
                boolean enriched = context.getHighCardinalityKeyValues().stream()
                        .anyMatch(kv -> kv.getKey().equals("rag.request.id"));
                if (enriched) {
                    events.add("span-enriched");
                }
                events.add("span-stopped:" + context.getName());
            }
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("the answer"))),
                ChatResponseMetadata.builder()
                        .model("test-model")
                        .usage(new DefaultUsage(10, 5, 15))
                        .build());
    }

    private static SemanticSearchResponse searchResponse() {
        SemanticSearchResponse.SearchHit hit = SemanticSearchResponse.SearchHit.builder()
                .rank(1)
                .score(0.9)
                .chunkText("chunk text")
                .sourceDocument(SemanticSearchResponse.SourceDocument.builder()
                        .documentId("doc-1").nodeId("node-1").sourceType("alfresco")
                        .name("report.pdf").path("/reports/report.pdf").build())
                .chunkMetadata(SemanticSearchResponse.ChunkMetadata.builder()
                        .embeddingId("emb-1").embeddingType("ai-mxbai-embed-large").build())
                .build();
        return SemanticSearchResponse.builder().results(List.of(hit)).build();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public Stream<T> stream() { return Stream.of(value); }
        };
    }
}
