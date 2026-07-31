package org.hyland.contentlake.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;

import java.util.ArrayList;
import java.util.List;

/**
 * Max Marginal Relevance (MMR) diversity selection.
 *
 * <p>Greedily builds a shortlist of {@code k} hits from an over-retrieved candidate pool. At each
 * step it picks the candidate maximising:</p>
 * <pre>
 *   lambda * relevance(c) - (1 - lambda) * max_{s in selected} cosine(c.vector, s.vector)
 * </pre>
 * <p>where {@code relevance(c)} is the retrieval score. {@code lambda = 1.0} is pure relevance
 * (original order), {@code lambda = 0.0} is pure diversity. Candidates without an embedding
 * vector (e.g. keyword-only hybrid hits) contribute a diversity penalty of 0, so they are ranked
 * on relevance alone and never rejected for missing vectors.</p>
 *
 * <p>Registered by {@link org.hyland.contentlake.rag.config.DiversityConfig} when
 * {@code rag.mmr.enabled} is true.</p>
 */
@Slf4j
public class MmrSelector implements DiversitySelector {

    private final RagProperties ragProperties;

    public MmrSelector(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public List<SearchHit> select(List<SearchHit> candidates, int k) {
        if (candidates == null || candidates.isEmpty() || k <= 0) {
            return List.of();
        }
        if (candidates.size() <= k) {
            return reRank(candidates);
        }

        double lambda = ragProperties.getMmr().getLambda();

        List<SearchHit> remaining = new ArrayList<>(candidates);
        List<SearchHit> selected = new ArrayList<>(k);

        while (selected.size() < k && !remaining.isEmpty()) {
            int bestIndex = 0;
            double bestScore = -Double.MAX_VALUE;

            for (int i = 0; i < remaining.size(); i++) {
                SearchHit candidate = remaining.get(i);
                double relevance = candidate.getScore();
                double maxSimToSelected = maxSimilarity(candidate, selected);
                double mmr = lambda * relevance - (1.0 - lambda) * maxSimToSelected;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIndex = i;
                }
            }

            selected.add(remaining.remove(bestIndex));
        }

        log.debug("MMR: {} candidates -> {} selected (lambda={})", candidates.size(), selected.size(), lambda);
        return reRank(selected);
    }

    private static double maxSimilarity(SearchHit candidate, List<SearchHit> selected) {
        if (selected.isEmpty() || candidate.getVector() == null || candidate.getVector().isEmpty()) {
            return 0.0;
        }
        double max = 0.0;
        for (SearchHit s : selected) {
            double sim = CosineSimilarity.cosine(candidate.getVector(), s.getVector());
            if (sim > max) {
                max = sim;
            }
        }
        return max;
    }

    /** Rebuilds the list with 1-based ranks reassigned to reflect selection order. */
    private static List<SearchHit> reRank(List<SearchHit> hits) {
        List<SearchHit> ranked = new ArrayList<>(hits.size());
        int rank = 1;
        for (SearchHit h : hits) {
            ranked.add(SearchHit.builder()
                    .rank(rank++)
                    .score(h.getScore())
                    .chunkText(h.getChunkText())
                    .sourceDocument(h.getSourceDocument())
                    .chunkMetadata(h.getChunkMetadata())
                    .vector(h.getVector())
                    .build());
        }
        return ranked;
    }
}
