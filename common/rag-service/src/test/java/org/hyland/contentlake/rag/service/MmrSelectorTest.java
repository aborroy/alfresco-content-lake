package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MmrSelectorTest {

    private static SearchHit hit(String text, double score, List<Double> vector) {
        return SearchHit.builder().chunkText(text).score(score).vector(vector).build();
    }

    private MmrSelector selectorWithLambda(double lambda) {
        RagProperties properties = new RagProperties();
        properties.getMmr().setLambda(lambda);
        return new MmrSelector(properties);
    }

    @Test
    void select_lambdaOne_pureRelevanceOrder() {
        // Two highly similar high-relevance hits and one dissimilar lower one.
        SearchHit a = hit("a", 0.90, List.of(1.0, 0.0));
        SearchHit b = hit("b", 0.85, List.of(0.99, 0.01));  // near-duplicate of a
        SearchHit c = hit("c", 0.50, List.of(0.0, 1.0));    // diverse but low relevance

        List<SearchHit> result = selectorWithLambda(1.0).select(List.of(a, b, c), 2);

        // Pure relevance: top two by score, ignoring redundancy.
        assertThat(result).extracting(SearchHit::getChunkText).containsExactly("a", "b");
    }

    @Test
    void select_lambdaZero_prefersDiversity() {
        SearchHit a = hit("a", 0.90, List.of(1.0, 0.0));
        SearchHit b = hit("b", 0.85, List.of(0.99, 0.01));  // near-duplicate of a
        SearchHit c = hit("c", 0.50, List.of(0.0, 1.0));    // diverse

        List<SearchHit> result = selectorWithLambda(0.0).select(List.of(a, b, c), 2);

        // First pick is highest relevance (a); second should be the diverse c, not the duplicate b.
        assertThat(result).extracting(SearchHit::getChunkText).containsExactly("a", "c");
    }

    @Test
    void select_reassignsRanks() {
        List<SearchHit> result = selectorWithLambda(0.5).select(
                List.of(hit("a", 0.9, List.of(1.0, 0.0)),
                        hit("b", 0.8, List.of(0.0, 1.0)),
                        hit("c", 0.7, List.of(1.0, 1.0))),
                2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getRank()).isEqualTo(1);
        assertThat(result.get(1).getRank()).isEqualTo(2);
    }

    @Test
    void select_nullVectors_handledAsRelevanceOnly() {
        SearchHit a = hit("a", 0.9, null);
        SearchHit b = hit("b", 0.6, null);
        SearchHit c = hit("c", 0.3, null);

        List<SearchHit> result = selectorWithLambda(0.5).select(List.of(a, b, c), 2);

        // No vectors -> diversity term is 0 -> falls back to relevance order.
        assertThat(result).extracting(SearchHit::getChunkText).containsExactly("a", "b");
    }

    @Test
    void select_kLargerThanCandidates_returnsAll() {
        List<SearchHit> result = selectorWithLambda(0.5).select(
                List.of(hit("a", 0.9, List.of(1.0)), hit("b", 0.8, List.of(0.5))), 10);

        assertThat(result).hasSize(2);
    }

    @Test
    void select_emptyOrNonPositiveK_returnsEmpty() {
        assertThat(selectorWithLambda(0.5).select(List.of(), 5)).isEmpty();
        assertThat(selectorWithLambda(0.5).select(List.of(hit("a", 0.9, List.of(1.0))), 0)).isEmpty();
    }
}
