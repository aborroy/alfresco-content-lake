package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;

import java.util.List;

/**
 * Selects a diverse shortlist from an over-retrieved candidate pool.
 *
 * <p>Runs between retrieval and reranking: it trims a large candidate pool down to
 * {@code k} hits, favouring passages that are both relevant and non-redundant so the
 * context window is not filled with near-duplicate chunks.</p>
 */
public interface DiversitySelector {

    /**
     * Selects up to {@code k} hits from {@code candidates}.
     *
     * @param candidates the over-retrieved candidate pool (retrieval order)
     * @param k          the maximum number of hits to return
     * @return the selected hits, ranks reassigned 1-based
     */
    List<SearchHit> select(List<SearchHit> candidates, int k);
}
