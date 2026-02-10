package org.alfresco.contentlake.batch.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Request payload for the semantic search validation endpoint.
 *
 * <p>Accepts a natural-language query that is embedded using the same model
 * ({@code mxbai-embed-large}) used during ingestion, then searched against
 * the {@code nuxeo_embeddings} index via kNN.</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SemanticSearchRequest {

    /**
     * Free-text query to embed and search.
     */
    private String query;

    /**
     * Maximum number of results to return (default 5, max 50).
     */
    private int topK = 5;

    /**
     * Optional HXQL filter to scope the search (appended to the permission filter).
     * Example: {@code "sys_primaryType = 'SysFile'"}
     */
    private String filter;

    /**
     * Embedding type to match. Defaults to wildcard ({@code "*"}) which matches all types.
     */
    private String embeddingType;

    /**
     * Minimum similarity score threshold (0.0 – 1.0). Results below this score are excluded.
     * Default 0.0 (no filtering).
     */
    private double minScore = 0.0;
}
