package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;

import java.util.List;

/**
 * Default {@link RetrievalGrader}: generates from whatever retrieval returned.
 *
 * <p>Preserves the behaviour that predates the gate, where any non-empty context reached the LLM.</p>
 */
public class NoOpRetrievalGrader implements RetrievalGrader {

    @Override
    public Verdict grade(String query, List<SearchHit> hits) {
        return Verdict.RELEVANT;
    }
}
