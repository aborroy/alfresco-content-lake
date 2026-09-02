package org.hyland.contentlake.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.hyland.contentlake.rag.conversation.ConversationMemoryService;
import org.hyland.contentlake.rag.conversation.ConversationTurn;
import org.hyland.contentlake.rag.conversation.SessionSummaryService;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.observability.RagObservations;
import org.hyland.contentlake.rag.model.HybridSearchRequest;
import org.hyland.contentlake.rag.model.RagPromptRequest;
import org.hyland.contentlake.rag.model.RagPromptResponse;
import org.hyland.contentlake.rag.model.ResponseFormat;
import org.hyland.contentlake.rag.model.StructuredAnswer;
import org.hyland.contentlake.rag.model.RagPromptResponse.ContextChunk;
import org.hyland.contentlake.rag.model.RagPromptResponse.Source;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.hyland.contentlake.security.SecurityContextService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * RAG (Retrieval-Augmented Generation) service.
 *
 * <p>Both the synchronous and streaming paths drive a single Spring AI {@link ChatClient}
 * whose default advisor ({@link ContentLakeRetrievalAdvisor}) performs the
 * retrieve -&gt; rerank -&gt; augment steps as a composable unit. This service is now
 * responsible only for:</p>
 * <ol>
 *   <li>conversation state (session resolution, history, query reformulation),</li>
 *   <li>invoking the {@link ChatClient} with the right advisor params, and</li>
 *   <li>mapping the reranked hits recorded in a {@link RetrievalTrace} into the response.</li>
 * </ol>
 *
 * <p>Retrieval uses {@link HybridSearchService} when {@code rag.use-hybrid-search=true}
 * (default) and {@link SemanticSearchService} otherwise; the choice lives in the retriever.
 * The context sent to the LLM is capped at {@code rag.max-context-length} characters by the
 * advisor.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\S+");

    @Value("${spring.ai.openai.chat.options.model:}")
    private String configuredModel;

    private final ChatClient ragChatClient;
    private final RagProperties ragProperties;
    private final ConversationMemoryService conversationMemoryService;
    private final QueryReformulationService queryReformulationService;
    private final SecurityContextService securityContextService;
    private final FilterInferenceService filterInferenceService;
    private final SessionSummaryService sessionSummaryService;
    private final CitationVerifier citationVerifier;
    private final StructuredAnswerService structuredAnswerService;
    private final RagToolset ragToolset;
    /** Optional (#73): null in unit tests that construct this service without the tracing collaborator. */
    private final RagObservations observations;

    /**
     * Executes the full RAG pipeline for a given question.
     *
     * @param request the RAG prompt request
     * @return response with generated answer, sources, and timing
     */
    public RagPromptResponse prompt(RagPromptRequest request) {
        long totalStart = System.currentTimeMillis();
        String requestId = java.util.UUID.randomUUID().toString();

        PromptContext promptContext = prepareContext(request);
        GenerationResult generation = generateAnswer(promptContext);
        long totalTimeMs = System.currentTimeMillis() - totalStart;

        persistConversationTurn(request, promptContext, generation);
        return buildPromptResponse(request, promptContext, generation, totalTimeMs, requestId);
    }

    /**
     * Streams LLM output token-by-token over SSE.
     *
     * <p>Emits:</p>
     * <ul>
     *   <li>{@code event: token} for every non-empty token delta</li>
     *   <li>{@code event: metadata} with the final {@link RagPromptResponse}</li>
     *   <li>{@code event: done} when the stream completes</li>
     *   <li>{@code event: error} on terminal failures</li>
     * </ul>
     */
    public SseEmitter streamPrompt(RagPromptRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        final long totalStart = System.currentTimeMillis();
        final String requestId = java.util.UUID.randomUUID().toString();

        final PromptContext promptContext;
        try {
            promptContext = prepareContext(request);
        } catch (Exception e) {
            log.error("RAG stream preparation failed: {}", e.getMessage(), e);
            sendErrorEvent(emitter, "Failed to prepare RAG stream: " + e.getMessage());
            emitter.complete();
            return emitter;
        }

        StringBuilder answerBuilder = new StringBuilder();
        StreamAccumulator accumulator = new StreamAccumulator();
        long generationStart = System.currentTimeMillis();

        Disposable subscription = requestSpec(promptContext)
                .stream()
                .chatResponse()
                .subscribe(chatResponse -> {
                            if (chatResponse == null) {
                                return;
                            }
                            updateStreamMetadata(accumulator, chatResponse);
                            String chunkText = extractChunkText(chatResponse);
                            String delta = resolveDeltaToken(answerBuilder, chunkText);
                            if (delta == null || delta.isEmpty()) {
                                return;
                            }
                            answerBuilder.append(delta);
                            sendTokenEvent(emitter, delta);
                        },
                        error -> {
                            log.error("RAG streaming generation failed: {}", error.getMessage(), error);
                            sendErrorEvent(emitter, error.getMessage());
                            emitter.complete();
                        },
                        () -> {
                            long generationTimeMs = System.currentTimeMillis() - generationStart;
                            String answer = answerBuilder.toString();
                            boolean hasContext = !promptContext.trace().rerankedHits().isEmpty();
                            GenerationResult generation = new GenerationResult(
                                    answer,
                                    hasContext ? resolveStreamModel(accumulator) : "none (no context available)",
                                    resolveStreamTokenCount(accumulator, answer),
                                    generationTimeMs
                            );
                            persistConversationTurn(request, promptContext, generation);
                            RagPromptResponse response = buildPromptResponse(
                                    request, promptContext, generation,
                                    System.currentTimeMillis() - totalStart, requestId
                            );
                            sendMetadataEvent(emitter, response);
                            sendDoneEvent(emitter);
                            emitter.complete();
                        });

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            subscription.dispose();
            emitter.complete();
        });

        return emitter;
    }

    // ---------------------------------------------------------------
    // ChatClient invocation
    // ---------------------------------------------------------------

    /**
     * Builds the {@link ChatClientRequestSpec} shared by the sync and stream paths.
     * The system + user messages carry the prompt; advisor params carry the reformulated
     * retrieval query, the conversation-history block, and the {@link RetrievalTrace} holder
     * that the advisor fills during retrieval.
     */
    private ChatClientRequestSpec requestSpec(PromptContext promptContext) {
        ChatClientRequestSpec spec = ragChatClient.prompt()
                .system(promptContext.systemPrompt())
                .user(promptContext.question())
                .advisors(advisor -> advisor
                        .param(ContentLakeRetrievalAdvisor.PARAM_RETRIEVAL_QUERY, promptContext.retrievalQuery())
                        .param(ContentLakeRetrievalAdvisor.PARAM_HISTORY_BLOCK, promptContext.historyBlock())
                        .param(RetrievalTrace.PARAM_KEY, promptContext.trace())
                        .param(HxprDocumentRetriever.CTX_TOP_K, promptContext.topK())
                        .param(HxprDocumentRetriever.CTX_MIN_SCORE, promptContext.minScore())
                        .params(promptContext.optionalRetrievalParams()));

        // Agentic tool-calling (#65): register the toolset and capture the request-thread
        // Authentication so tools apply the same ACL filtering even when executed on a Reactor
        // (streaming) thread. Off by default; identity is never taken from tool arguments.
        if (ragProperties.getAgenticTools().isEnabled()) {
            Map<String, Object> toolContext = new HashMap<>();
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                toolContext.put(RagToolset.CTX_AUTH, auth);
            }
            toolContext.put(RagToolset.CTX_ITERATIONS, new AtomicInteger(0));
            spec = spec.tools(ragToolset).toolContext(toolContext);
        }
        return spec;
    }

    private GenerationResult generateAnswer(PromptContext promptContext) {
        long generationStart = System.currentTimeMillis();

        try {
            ChatResponse chatResponse = traced("rag.generate",
                    () -> requestSpec(promptContext).call().chatResponse());
            long generationTimeMs = System.currentTimeMillis() - generationStart;

            boolean hasContext = !promptContext.trace().rerankedHits().isEmpty();
            String answer = chatResponse != null && chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : "";
            String modelName = hasContext ? resolveModelName(chatResponse) : "none (no context available)";
            Integer tokenCount = hasContext ? resolveTokenCount(chatResponse) : null;

            log.info("RAG generate phase complete: model={}, answer length={} chars",
                    modelName, answer != null ? answer.length() : 0);

            return new GenerationResult(answer, modelName, tokenCount, generationTimeMs);
        } catch (Exception e) {
            log.error("LLM generation failed: {}", e.getMessage(), e);
            long generationTimeMs = System.currentTimeMillis() - generationStart;
            return new GenerationResult(
                    "An error occurred while generating the answer: " + e.getMessage(),
                    "error",
                    null,
                    generationTimeMs
            );
        }
    }

    /** Runs {@code work} inside a named tracing span (#73) when observation is wired; otherwise inline. */
    private <T> T traced(String name, java.util.function.Supplier<T> work) {
        return observations != null ? observations.observe(name, work) : work.get();
    }

    private String resolveModelName(ChatResponse chatResponse) {
        if (configuredModel != null && !configuredModel.isBlank()) {
            return configuredModel;
        }
        if (chatResponse != null && chatResponse.getMetadata() != null && chatResponse.getMetadata().getModel() != null) {
            return chatResponse.getMetadata().getModel();
        }
        return "unknown";
    }

    private Integer resolveTokenCount(ChatResponse chatResponse) {
        if (chatResponse != null && chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            return chatResponse.getMetadata().getUsage().getTotalTokens();
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Preparation (conversation state + retrieval query + prompt)
    // ---------------------------------------------------------------

    private PromptContext prepareContext(RagPromptRequest request) {
        ConversationState conversation = prepareConversationState(request);
        String retrievalQuery = resolveRetrievalQuery(request.getQuestion(), conversation.history());
        String historyBlock = assembleConversationHistory(conversation.history());
        // Long-term memory: prepend the persisted running summary so facts and intent from before the
        // sliding window (or before a restart) still reach the model. No-op when the feature is off.
        historyBlock = prependSessionSummary(conversation, historyBlock);
        String systemPrompt = resolveSystemPrompt(request);

        int topK = request.getTopK() > 0 ? request.getTopK() : ragProperties.getDefaultTopK();
        // An explicit 0.0 means "no threshold" and must survive; only an absent value falls back.
        double minScore = request.getMinScore() != null
                ? request.getMinScore()
                : ragProperties.getDefaultMinScore();

        // Intent-aware filter inference (opt-in). Additive to any caller-supplied filter; the
        // service returns null on failure so retrieval simply proceeds unfiltered.
        HybridSearchRequest.MetadataFilter inferredFilter = request.isInferFilters()
                ? filterInferenceService.infer(request.getQuestion())
                : null;

        return new PromptContext(
                conversation,
                request.getQuestion(),
                retrievalQuery,
                historyBlock,
                systemPrompt,
                topK,
                minScore,
                request.getFilter(),
                request.getSourceType(),
                request.getEmbeddingType(),
                inferredFilter,
                new RetrievalTrace()
        );
    }

    private ConversationState prepareConversationState(RagPromptRequest request) {
        boolean conversationEnabled = ragProperties.getConversation().isEnabled();
        if (!conversationEnabled) {
            return new ConversationState(false, null, List.of());
        }

        String sessionId = resolveSessionId(request);
        if (request.isResetSession()) {
            conversationMemoryService.resetSession(sessionId);
        }

        List<ConversationTurn> history = conversationMemoryService.getRecentTurns(sessionId);
        return new ConversationState(true, sessionId, history != null ? history : List.of());
    }

    private void persistConversationTurn(RagPromptRequest request,
                                         PromptContext promptContext,
                                         GenerationResult generation) {
        if (!promptContext.conversation().enabled()) {
            return;
        }
        conversationMemoryService.appendUserTurn(promptContext.conversation().sessionId(), request.getQuestion());
        conversationMemoryService.appendAssistantTurn(promptContext.conversation().sessionId(), generation.answer());
    }

    private RagPromptResponse buildPromptResponse(RagPromptRequest request,
                                                  PromptContext promptContext,
                                                  GenerationResult generation,
                                                  long totalTimeMs,
                                                  String requestId) {
        List<SearchHit> rerankedHits = promptContext.trace().rerankedHits();
        List<Source> sources = mapSources(rerankedHits);
        List<ContextChunk> contextChunks = request.isIncludeContext() ? mapContextChunks(rerankedHits) : null;
        String retrievalQuery = promptContext.trace().retrievalQuery() != null
                ? promptContext.trace().retrievalQuery()
                : promptContext.retrievalQuery();

        // Post-generation faithfulness check (opt-in). Null result leaves both fields absent.
        CitationVerifier.VerificationResult verification =
                citationVerifier.verify(generation.answer(), rerankedHits);

        // Structured/typed output (#70). Opt-in second pass; skipped when no context was retrieved.
        StructuredAnswer structured = null;
        if (request.getResponseFormat() == ResponseFormat.STRUCTURED && !rerankedHits.isEmpty()) {
            structured = structuredAnswerService.summarize(
                    request.getQuestion(), generation.answer(), rerankedHits);
        }

        return RagPromptResponse.builder()
                .answer(generation.answer())
                .requestId(requestId)
                .question(request.getQuestion())
                .sessionId(promptContext.conversation().sessionId())
                .retrievalQuery(retrievalQuery)
                .historyTurnsUsed(promptContext.conversation().enabled()
                        ? promptContext.conversation().history().size()
                        : null)
                .model(generation.modelName())
                .tokenCount(generation.tokenCount())
                .searchTimeMs(promptContext.trace().searchTimeMs())
                .generationTimeMs(generation.generationTimeMs())
                .totalTimeMs(totalTimeMs)
                .sourcesUsed(sources.size())
                .sources(sources)
                .context(contextChunks)
                .verified(verification != null ? verification.verified() : null)
                .unsupportedClaims(verification != null ? verification.unsupportedClaims() : null)
                .structured(structured)
                .build();
    }

    private List<Source> mapSources(List<SearchHit> hits) {
        return hits.stream()
                .map(hit -> Source.builder()
                        .documentId(hit.getSourceDocument() != null ? hit.getSourceDocument().getDocumentId() : null)
                        .nodeId(hit.getSourceDocument() != null ? hit.getSourceDocument().getNodeId() : null)
                        .sourceId(hit.getSourceDocument() != null ? hit.getSourceDocument().getSourceId() : null)
                        .sourceType(hit.getSourceDocument() != null ? hit.getSourceDocument().getSourceType() : null)
                        .name(hit.getSourceDocument() != null ? hit.getSourceDocument().getName() : null)
                        .path(hit.getSourceDocument() != null ? hit.getSourceDocument().getPath() : null)
                        .openInSourceUrl(hit.getSourceDocument() != null ? hit.getSourceDocument().getOpenInSourceUrl() : null)
                        .chunkText(hit.getChunkText())
                        .score(hit.getScore())
                        .build())
                .toList();
    }

    private List<ContextChunk> mapContextChunks(List<SearchHit> hits) {
        return hits.stream()
                .map(hit -> ContextChunk.builder()
                        .rank(hit.getRank())
                        .score(hit.getScore())
                        .text(hit.getChunkText())
                        .sourceName(hit.getSourceDocument() != null ? hit.getSourceDocument().getName() : null)
                        .sourcePath(hit.getSourceDocument() != null ? hit.getSourceDocument().getPath() : null)
                        .sourceType(hit.getSourceDocument() != null ? hit.getSourceDocument().getSourceType() : null)
                        .openInSourceUrl(hit.getSourceDocument() != null ? hit.getSourceDocument().getOpenInSourceUrl() : null)
                        .build())
                .toList();
    }

    // ---------------------------------------------------------------
    // SSE helpers
    // ---------------------------------------------------------------

    private void sendTokenEvent(SseEmitter emitter, String token) {
        try {
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(Map.of("token", token)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to send SSE token event", e);
        }
    }

    private void sendDoneEvent(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("status", "ok")));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to send SSE done event", e);
        }
    }

    private void sendMetadataEvent(SseEmitter emitter, RagPromptResponse response) {
        try {
            emitter.send(SseEmitter.event()
                    .name("metadata")
                    .data(response));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to send SSE metadata event", e);
        }
    }

    private void sendErrorEvent(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", message != null && !message.isBlank() ? message : "Stream failed")));
        } catch (IOException e) {
            log.warn("Unable to send SSE error event: {}", e.getMessage());
        }
    }

    private void updateStreamMetadata(StreamAccumulator accumulator, ChatResponse chatResponse) {
        if (chatResponse.getMetadata() == null) {
            return;
        }

        String model = chatResponse.getMetadata().getModel();
        if (model != null && !model.isBlank()) {
            accumulator.model = model;
        }

        if (chatResponse.getMetadata().getUsage() != null) {
            Integer totalTokens = chatResponse.getMetadata().getUsage().getTotalTokens();
            if (totalTokens != null && totalTokens > 0) {
                accumulator.tokenCount = totalTokens;
            }
        }
    }

    private String extractChunkText(ChatResponse chatResponse) {
        if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        String text = chatResponse.getResult().getOutput().getText();
        return text != null ? text : "";
    }

    private String resolveDeltaToken(StringBuilder currentAnswer, String chunkText) {
        if (chunkText == null || chunkText.isEmpty()) {
            return "";
        }
        String existing = currentAnswer.toString();
        if (!existing.isEmpty() && chunkText.startsWith(existing)) {
            return chunkText.substring(existing.length());
        }
        return chunkText;
    }

    private String resolveStreamModel(StreamAccumulator accumulator) {
        if (configuredModel != null && !configuredModel.isBlank()) {
            return configuredModel;
        }
        if (accumulator.model != null && !accumulator.model.isBlank()) {
            return accumulator.model;
        }
        return "unknown";
    }

    private Integer resolveStreamTokenCount(StreamAccumulator accumulator, String answer) {
        if (accumulator.tokenCount != null && accumulator.tokenCount > 0) {
            return accumulator.tokenCount;
        }
        return estimateTokenCount(answer);
    }

    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        var matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------------
    // Prompt building / conversation
    // ---------------------------------------------------------------

    private String resolveSystemPrompt(RagPromptRequest request) {
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            return request.getSystemPrompt();
        }
        return ragProperties.getDefaultSystemPrompt();
    }

    private String prependSessionSummary(ConversationState conversation, String historyBlock) {
        if (!conversation.enabled() || conversation.sessionId() == null
                || !sessionSummaryService.isEnabled()) {
            return historyBlock;
        }
        String summary = sessionSummaryService.loadSummary(conversation.sessionId());
        if (summary == null || summary.isBlank()) {
            return historyBlock;
        }
        String summaryBlock = "Conversation summary so far:\n" + summary.trim();
        return (historyBlock == null || historyBlock.isBlank())
                ? summaryBlock
                : summaryBlock + "\n\n" + historyBlock;
    }

    private String assembleConversationHistory(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }

        StringBuilder history = new StringBuilder();
        for (ConversationTurn turn : turns) {
            String role = turn.getRole() == ConversationTurn.Role.ASSISTANT ? "Assistant" : "User";
            if (turn.getContent() != null && !turn.getContent().isBlank()) {
                history.append(role).append(": ").append(turn.getContent().trim()).append("\n");
            }
        }
        return history.toString().trim();
    }

    private String resolveRetrievalQuery(String originalQuestion, List<ConversationTurn> history) {
        if (!ragProperties.getConversation().isEnabled()) {
            return originalQuestion;
        }
        if (!ragProperties.getConversation().isQueryReformulation() || history.isEmpty()) {
            return originalQuestion;
        }
        String rewritten = queryReformulationService.reformulate(originalQuestion, history);
        if (rewritten == null || rewritten.isBlank()) {
            return originalQuestion;
        }
        return rewritten;
    }

    private String resolveSessionId(RagPromptRequest request) {
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            return request.getSessionId().trim();
        }
        String username = securityContextService.getCurrentUsername();
        if (username == null || username.isBlank()) {
            return "user:anonymous";
        }
        return "user:" + username.trim();
    }

    // ---------------------------------------------------------------
    // Pipeline state
    // ---------------------------------------------------------------

    private record ConversationState(boolean enabled, String sessionId, List<ConversationTurn> history) {
    }

    private record PromptContext(ConversationState conversation,
                                 String question,
                                 String retrievalQuery,
                                 String historyBlock,
                                 String systemPrompt,
                                 int topK,
                                 double minScore,
                                 String filter,
                                 String sourceType,
                                 String embeddingType,
                                 HybridSearchRequest.MetadataFilter metadataFilter,
                                 RetrievalTrace trace) {

        /** Non-null optional retrieval params for the advisor context (filter/sourceType/embeddingType/metadata). */
        Map<String, Object> optionalRetrievalParams() {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (filter != null) {
                params.put(HxprDocumentRetriever.CTX_FILTER, filter);
            }
            if (sourceType != null) {
                params.put(HxprDocumentRetriever.CTX_SOURCE_TYPE, sourceType);
            }
            if (embeddingType != null) {
                params.put(HxprDocumentRetriever.CTX_EMBEDDING_TYPE, embeddingType);
            }
            if (metadataFilter != null) {
                params.put(HxprDocumentRetriever.CTX_METADATA_FILTER, metadataFilter);
            }
            return params;
        }
    }

    private record GenerationResult(String answer, String modelName, Integer tokenCount, long generationTimeMs) {
    }

    private static final class StreamAccumulator {
        private String model;
        private Integer tokenCount;
    }
}
