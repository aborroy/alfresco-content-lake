package org.alfresco.contentlake.batch.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.contentlake.batch.model.SemanticSearchRequest;
import org.alfresco.contentlake.batch.model.SemanticSearchResponse;
import org.alfresco.contentlake.batch.service.SemanticSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for semantic (vector) search validation.
 *
 * <p>Provides endpoints to test embedding quality and search relevance against
 * the {@code nuxeo_embeddings} index before the full RAG pipeline is built.</p>
 *
 * <p>All endpoints require Alfresco authentication (Basic Auth or ticket).
 * Results are filtered by the authenticated user's document permissions using
 * {@code sys_racl} authority matching.</p>
 *
 * <h3>Usage examples</h3>
 * <pre>
 * # Basic semantic search
 * curl -u admin:admin -X POST http://localhost:9090/api/search/semantic \
 *   -H "Content-Type: application/json" \
 *   -d '{"query": "quarterly financial report", "topK": 5}'
 *
 * # With minimum score threshold and additional filter
 * curl -u admin:admin -X POST http://localhost:9090/api/search/semantic \
 *   -H "Content-Type: application/json" \
 *   -d '{"query": "project timeline", "topK": 10, "minScore": 0.5,
 *        "filter": "sys_primaryType = '\''SysFile'\''"}'
 *
 * # Check embedding service health
 * curl -u admin:admin http://localhost:9090/api/search/semantic/health
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/search/semantic")
@RequiredArgsConstructor
public class SemanticSearchController {

    private final SemanticSearchService semanticSearchService;

    /**
     * Executes a semantic search against the embedded chunks.
     *
     * <p>Converts the query text to an embedding vector using the same model
     * ({@code mxbai-embed-large}) used during ingestion, then performs kNN
     * search against the HXPR embeddings index. Results are enriched with
     * parent document metadata and filtered by user permissions.</p>
     *
     * @param request search parameters (query, topK, filter, minScore)
     * @return ranked search results with similarity scores and metadata
     */
    @PostMapping
    public ResponseEntity<SemanticSearchResponse> search(@RequestBody SemanticSearchRequest request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest().body(
                    SemanticSearchResponse.builder()
                            .query("")
                            .resultCount(0)
                            .totalCount(0)
                            .build()
            );
        }

        log.debug("Semantic search request: query=\"{}\", topK={}, minScore={}",
                request.getQuery(), request.getTopK(), request.getMinScore());

        SemanticSearchResponse response = semanticSearchService.search(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint for the semantic search subsystem.
     *
     * <p>Verifies that the embedding model is reachable by embedding a short
     * test string. Returns the model name, vector dimensionality, and status.</p>
     *
     * @return health status including model and index information
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            var embedding = semanticSearchService.search(
                    SemanticSearchRequest.builder().query("health check").topK(1).build()
            );

            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "model", embedding.getModel() != null ? embedding.getModel() : "unknown",
                    "vectorDimension", embedding.getVectorDimension(),
                    "searchTimeMs", embedding.getSearchTimeMs(),
                    "indexReachable", true
            ));
        } catch (Exception e) {
            log.error("Semantic search health check failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "status", "DOWN",
                    "error", e.getMessage() != null ? e.getMessage() : "Unknown error",
                    "indexReachable", false
            ));
        }
    }

}
