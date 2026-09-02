package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;

import java.util.List;

/**
 * Mutable per-invocation holder for retrieval diagnostics produced by
 * {@link ContentLakeRetrievalAdvisor} and read back by {@code RagService}.
 *
 * <p>A fresh instance is created per RAG request, passed to the advisor through the
 * {@code ChatClient} advisor params, and populated during the advisor's {@code before}
 * phase. This lets the service build its {@code RagPromptResponse} (sources, timing,
 * reranked hit count) without threading data through the Spring AI response context,
 * which is awkward for the streaming path.</p>
 */
public final class RetrievalTrace {

    /** Advisor param key under which this holder travels in the ChatClient request context. */
    public static final String PARAM_KEY = "cl.retrievalTrace";

    private volatile String retrievalQuery;
    private volatile long searchTimeMs;
    private volatile List<SearchHit> rerankedHits = List.of();

    public String retrievalQuery() {
        return retrievalQuery;
    }

    public long searchTimeMs() {
        return searchTimeMs;
    }

    public List<SearchHit> rerankedHits() {
        return rerankedHits;
    }

    void record(String retrievalQuery, long searchTimeMs, List<SearchHit> rerankedHits) {
        this.retrievalQuery = retrievalQuery;
        this.searchTimeMs = searchTimeMs;
        this.rerankedHits = rerankedHits != null ? rerankedHits : List.of();
    }
}
