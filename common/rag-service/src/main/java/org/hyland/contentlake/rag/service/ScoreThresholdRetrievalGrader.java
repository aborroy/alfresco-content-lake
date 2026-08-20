package org.hyland.contentlake.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;

import java.util.List;

/**
 * Score-based {@link RetrievalGrader}: relevant when enough hits clear a threshold.
 *
 * <p>Deliberately not an LLM call. The gate exists to avoid paying for a generation that cannot be
 * grounded, so spending a generation-sized call to decide would defeat it; and it runs on the
 * synchronous request thread, where this module has no timeout convention to fall back on.</p>
 *
 * <p>Two things distinguish this from simply raising {@code minScore} on the search request. It grades
 * the <em>reranked</em> set, after diversity selection and reranking have had their say, so it sees the
 * context the LLM would actually be given. And a weak verdict does not silently return fewer results:
 * it triggers a broadened retry, and only then declines to answer.</p>
 *
 * <p>The threshold is scale-dependent. On the default hybrid path a hit's score is a fusion value, not
 * a cosine: roughly 0.02-0.03 under {@code rrf} and 0-1 under {@code weighted}/{@code minmax}. A value
 * borrowed from one strategy discards everything under the other, which is why
 * {@code rag.retrieval-grading.min-score} defaults to 0.0 and has to be measured against the
 * configured strategy.</p>
 */
@Slf4j
public class ScoreThresholdRetrievalGrader implements RetrievalGrader {

    private final RagProperties ragProperties;

    public ScoreThresholdRetrievalGrader(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public Verdict grade(String query, List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return Verdict.WEAK;
        }

        RagProperties.RetrievalGradingProperties grading = ragProperties.getRetrievalGrading();
        double minScore = grading.getMinScore();
        int minHits = Math.max(1, grading.getMinHits());

        long clearing = hits.stream().filter(hit -> hit.getScore() >= minScore).count();
        if (clearing >= minHits) {
            return Verdict.RELEVANT;
        }

        log.info("Retrieval graded WEAK: {} of {} hits cleared minScore={} (need {})",
                clearing, hits.size(), minScore, minHits);
        return Verdict.WEAK;
    }
}
