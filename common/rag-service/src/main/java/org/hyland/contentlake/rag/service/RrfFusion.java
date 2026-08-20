package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion over several ranked {@link SearchHit} lists.
 *
 * <p>Sibling of {@code HybridSearchService.fuseRRF}, one level up: that one fuses the vector and
 * keyword legs of a single query, this one fuses the result sets of several query variants. The
 * formula is the same, {@code sum(1 / (k + rank))} across the lists a chunk appears in, which rewards
 * chunks that more than one formulation found without needing the legs' scores to be comparable.</p>
 *
 * <p>Fusion decides <em>order</em> only. Each surviving hit keeps the {@code score} its own retrieval
 * pass gave it, the same convention {@link LlmRerankService} follows, because that score is a cosine
 * or fusion value that callers threshold and display. An RRF score of 0.031 in that field would be
 * meaningless to every consumer downstream.</p>
 */
final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * Fuses ranked lists into one, deduplicating on chunk identity.
     *
     * <p>Input lists are read in the order given and may be null or empty. Ranks are taken from each
     * hit's own {@code rank} when set, falling back to its position in the list, so a list that has
     * been through reranking fuses on its reranked order.</p>
     *
     * @param rankedLists per-variant result sets
     * @param k           RRF constant; larger values flatten the contribution of top ranks
     * @param limit       maximum hits to return; non-positive means no limit
     * @return fused hits, ordered best first, with {@code rank} reassigned from 1
     */
    static List<SearchHit> fuse(List<List<SearchHit>> rankedLists, int k, int limit) {
        if (rankedLists == null || rankedLists.isEmpty()) {
            return List.of();
        }

        Map<String, Fused> fused = new LinkedHashMap<>();
        for (List<SearchHit> list : rankedLists) {
            if (list == null) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                SearchHit hit = list.get(i);
                if (hit == null) {
                    continue;
                }
                int rank = hit.getRank() > 0 ? hit.getRank() : i + 1;
                fused.computeIfAbsent(keyOf(hit, i), key -> new Fused(hit)).score += 1.0 / (k + rank);
            }
        }

        List<Fused> ordered = new ArrayList<>(fused.values());
        // Stable sort: chunks tied on RRF score keep the order of the variant that found them first,
        // which is the original query.
        ordered.sort(Comparator.comparingDouble((Fused f) -> f.score).reversed());

        int cap = limit > 0 ? Math.min(limit, ordered.size()) : ordered.size();
        List<SearchHit> results = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            SearchHit hit = ordered.get(i).hit;
            results.add(SearchHit.builder()
                    .rank(i + 1)
                    .score(hit.getScore())
                    .chunkText(hit.getChunkText())
                    .sourceDocument(hit.getSourceDocument())
                    .chunkMetadata(hit.getChunkMetadata())
                    .vector(hit.getVector())
                    .build());
        }
        return results;
    }

    /**
     * Chunk identity, mirroring {@code HybridSearchService.chunkKey}: document id plus embedding id.
     *
     * <p>Falls back to the chunk text when either id is missing, and finally to a per-position key.
     * A weaker fallback than text would collapse distinct chunks into one and silently lose results;
     * this errs the other way, letting a duplicate through rather than dropping a real hit.</p>
     */
    private static String keyOf(SearchHit hit, int position) {
        String docId = hit.getSourceDocument() != null ? hit.getSourceDocument().getDocumentId() : null;
        String embeddingId = hit.getChunkMetadata() != null ? hit.getChunkMetadata().getEmbeddingId() : null;
        if (docId != null && embeddingId != null) {
            return docId + "#" + embeddingId;
        }
        if (hit.getChunkText() != null && !hit.getChunkText().isBlank()) {
            return "text:" + hit.getChunkText();
        }
        return "pos:" + position;
    }

    private static final class Fused {

        private final SearchHit hit;
        private double score;

        private Fused(SearchHit hit) {
            this.hit = hit;
        }
    }
}
