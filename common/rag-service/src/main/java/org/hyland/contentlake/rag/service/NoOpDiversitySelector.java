package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;

import java.util.List;

/**
 * Identity {@link DiversitySelector}: returns the first {@code k} candidates in retrieval order,
 * applying no diversity logic. Registered by
 * {@link org.hyland.contentlake.rag.config.DiversityConfig} when MMR is disabled.
 */
public class NoOpDiversitySelector implements DiversitySelector {

    @Override
    public List<SearchHit> select(List<SearchHit> candidates, int k) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (k <= 0) {
            return List.of();
        }
        return candidates.size() <= k ? List.copyOf(candidates) : List.copyOf(candidates.subList(0, k));
    }
}
