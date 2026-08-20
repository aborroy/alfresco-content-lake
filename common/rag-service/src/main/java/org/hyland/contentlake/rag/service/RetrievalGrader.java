package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;

import java.util.List;

/**
 * Judges whether a retrieved context is strong enough to answer a question, before the generation
 * call is made.
 *
 * <p>Complements post-generation verification: this decides whether generating is worth doing at all.
 * Without it, a handful of barely-relevant chunks still buys a full LLM call and returns a
 * confidently-worded answer that nothing in the corpus actually supports.</p>
 *
 * @see ScoreThresholdRetrievalGrader
 */
public interface RetrievalGrader {

    /** Grade for a retrieved context. */
    enum Verdict {

        /** Good enough to generate from. */
        RELEVANT,

        /** Too weak to ground an answer; broaden the retrieval or decline to answer. */
        WEAK
    }

    /**
     * Grades a reranked result set against the query it came from.
     *
     * @param query the retrieval query
     * @param hits  the reranked hits, best first; may be empty
     * @return {@link Verdict#RELEVANT} or {@link Verdict#WEAK}
     */
    Verdict grade(String query, List<SearchHit> hits);
}
