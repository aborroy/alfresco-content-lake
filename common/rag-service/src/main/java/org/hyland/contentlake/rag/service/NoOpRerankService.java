package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;

import java.util.List;

/**
 * Default reranker that keeps current ordering unchanged.
 * Registered by {@link org.hyland.contentlake.rag.config.RerankServiceConfig} when no
 * reranker URL is configured.
 */
public class NoOpRerankService implements RerankService {

    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return List.copyOf(hits);
    }
}
