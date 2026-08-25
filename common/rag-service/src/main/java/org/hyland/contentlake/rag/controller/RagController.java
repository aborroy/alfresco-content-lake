package org.hyland.contentlake.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.model.RagPromptRequest;
import org.hyland.contentlake.rag.model.RagPromptResponse;
import org.hyland.contentlake.rag.service.CommunitySummaryService;
import org.hyland.contentlake.rag.service.RagService;
import org.hyland.contentlake.rag.service.SemanticSearchService;
import org.hyland.contentlake.rag.model.SemanticSearchRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for RAG (Retrieval-Augmented Generation) operations.
 *
 * <p>Provides the main prompt endpoint that combines semantic search with LLM generation
 * to answer questions grounded in Alfresco document content.</p>
 *
 * <p>All endpoints require Alfresco authentication (Basic Auth or ticket).
 * Search results are filtered by the authenticated user's document permissions.</p>
 *
 * <h3>Usage examples</h3>
 * <pre>
 * # Ask a question
 * curl -u admin:admin -X POST http://localhost:9091/api/rag/prompt \
 *   -H "Content-Type: application/json" \
 *   -d '{"question": "What are the key findings in the Q4 report?"}'
 *
 * # With options
 * curl -u admin:admin -X POST http://localhost:9091/api/rag/prompt \
 *   -H "Content-Type: application/json" \
 *   -d '{"question": "Summarize the budget proposal", "topK": 10, "minScore": 0.6, "includeContext": true}'
 *
 * # Health check
 * curl -u admin:admin http://localhost:9091/api/rag/health
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;
    private final SemanticSearchService semanticSearchService;
    private final ChatModel chatModel;
    /** Present only when rag.graph.enabled=true (community summaries, #56). */
    private final ObjectProvider<CommunitySummaryService> communitySummaryServiceProvider;

    /**
     * Executes the RAG pipeline: retrieve relevant chunks, augment with context, generate answer.
     *
     * @param request RAG prompt parameters (question, sessionId, resetSession, topK, minScore, filter, systemPrompt, includeContext)
     * @return generated answer with sources and timing breakdown
     */
    @PostMapping("/prompt")
    public ResponseEntity<RagPromptResponse> prompt(@RequestBody RagPromptRequest request) {
        if (hasInvalidQuestion(request)) {
            return ResponseEntity.badRequest().body(
                    RagPromptResponse.builder()
                            .answer("Question is required")
                            .question("")
                            .sourcesUsed(0)
                            .build()
            );
        }

        log.info("RAG prompt request: question=\"{}\", sessionId={}, resetSession={}, topK={}, minScore={}, includeContext={}",
                request.getQuestion(), request.getSessionId(), request.isResetSession(),
                request.getTopK(), request.getMinScore(), request.isIncludeContext());

        RagPromptResponse response = ragService.prompt(request);
        return ResponseEntity.ok(response);
    }

    /**
     * GraphRAG variant of {@link #prompt}: same contract, but graph-augmented retrieval is forced on
     * for this request. After vector/hybrid retrieval the pipeline traverses the knowledge graph to
     * include related documents; the response adds {@code graphEntities} and {@code graphSources}.
     * Requires {@code rag.graph.enabled=true} (otherwise it behaves like {@code /prompt}).
     *
     * @param request same fields as {@code /prompt}; {@code graphHops} / {@code includeCommunities}
     *                are honoured, {@code useGraphExpansion} is forced true
     * @return generated answer with vector sources plus graph-expanded sources and entities
     */
    @PostMapping("/graph-prompt")
    public ResponseEntity<RagPromptResponse> graphPrompt(@RequestBody RagPromptRequest request) {
        if (hasInvalidQuestion(request)) {
            return ResponseEntity.badRequest().body(
                    RagPromptResponse.builder()
                            .answer("Question is required")
                            .question("")
                            .sourcesUsed(0)
                            .build()
            );
        }

        request.setUseGraphExpansion(true);
        log.info("RAG graph-prompt request: question=\"{}\", sessionId={}, graphHops={}, includeCommunities={}",
                request.getQuestion(), request.getSessionId(), request.getGraphHops(), request.isIncludeCommunities());

        RagPromptResponse response = ragService.prompt(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Rebuilds graph community summaries (#56) from the current knowledge graph: clusters documents by
     * shared entity, generates an LLM summary per community, and stores each as a permission-filtered
     * hxpr document. Corpus-level and idempotent; run after ingestion/extraction has populated the graph.
     * Requires {@code rag.graph.enabled=true}.
     *
     * @return the number of community summaries written
     */
    @PostMapping("/graph/communities/rebuild")
    public ResponseEntity<Map<String, Object>> rebuildCommunities() {
        CommunitySummaryService service = communitySummaryServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Graph is disabled; set rag.graph.enabled=true to build community summaries."));
        }
        int count = service.rebuild();
        log.info("Community rebuild via API produced {} communities", count);
        return ResponseEntity.ok(Map.of("communities", count));
    }

    /**
     * Streams RAG answer generation over SSE (canonical GET contract).
     *
     * <p>Request parameters map to the same fields as {@code /prompt}.</p>
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamGet(@ModelAttribute RagPromptRequest request) {
        if (hasInvalidQuestion(request)) {
            return ResponseEntity.badRequest().build();
        }
        log.info("RAG stream request (GET): question=\"{}\", sessionId={}, topK={}, minScore={}",
                request.getQuestion(), request.getSessionId(), request.getTopK(), request.getMinScore());
        return ResponseEntity.ok(ragService.streamPrompt(request));
    }

    /**
     * Streams RAG answer generation over SSE (compatibility POST contract).
     *
     * <p>This exists to keep current UI integration working while GET remains canonical.</p>
     */
    @PostMapping(value = "/chat/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamPost(@RequestBody RagPromptRequest request) {
        if (hasInvalidQuestion(request)) {
            return ResponseEntity.badRequest().build();
        }
        log.info("RAG stream request (POST): question=\"{}\", sessionId={}, topK={}, minScore={}",
                request.getQuestion(), request.getSessionId(), request.getTopK(), request.getMinScore());
        return ResponseEntity.ok(ragService.streamPrompt(request));
    }

    /**
     * Health check endpoint for the RAG service.
     *
     * <p>Verifies connectivity to:
     * <ul>
     *   <li>Embedding model (via a test embedding)</li>
     *   <li>LLM (via ChatModel availability)</li>
     *   <li>hxpr vector index (via a test search)</li>
     * </ul>
     *
     * @return health status for each component
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();

        // Check embedding + hxpr
        try {
            var searchResult = semanticSearchService.search(
                    SemanticSearchRequest.builder().query("health check").topK(1).build()
            );
            health.put("embedding", Map.of(
                    "status", "UP",
                    "model", searchResult.getModel() != null ? searchResult.getModel() : "unknown",
                    "vectorDimension", searchResult.getVectorDimension()
            ));
            health.put("hxpr", Map.of(
                    "status", "UP",
                    "searchTimeMs", searchResult.getSearchTimeMs()
            ));
        } catch (Exception e) {
            log.error("Embedding/hxpr health check failed: {}", e.getMessage());
            health.put("embedding", Map.of("status", "DOWN", "error", e.getMessage()));
            health.put("hxpr", Map.of("status", "DOWN"));
        }

        // Check LLM
        try {
            boolean llmAvailable = chatModel != null;
            health.put("llm", Map.of(
                    "status", llmAvailable ? "UP" : "DOWN"
            ));
        } catch (Exception e) {
            log.error("LLM health check failed: {}", e.getMessage());
            health.put("llm", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        boolean allUp = health.values().stream()
                .filter(v -> v instanceof Map)
                .allMatch(v -> "UP".equals(((Map<?, ?>) v).get("status")));

        health.put("status", allUp ? "UP" : "DEGRADED");

        return ResponseEntity.ok(health);
    }

    private boolean hasInvalidQuestion(RagPromptRequest request) {
        return request == null || request.getQuestion() == null || request.getQuestion().isBlank();
    }
}
