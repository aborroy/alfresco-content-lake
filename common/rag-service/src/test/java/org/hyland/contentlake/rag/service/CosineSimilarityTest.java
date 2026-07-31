package org.hyland.contentlake.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CosineSimilarityTest {

    @Test
    void cosine_identicalVectors_returnsOne() {
        assertThat(CosineSimilarity.cosine(List.of(1.0, 2.0, 3.0), List.of(1.0, 2.0, 3.0)))
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void cosine_parallelVectors_returnsOne() {
        assertThat(CosineSimilarity.cosine(List.of(1.0, 0.0), List.of(3.0, 0.0)))
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void cosine_orthogonalVectors_returnsZero() {
        assertThat(CosineSimilarity.cosine(List.of(1.0, 0.0), List.of(0.0, 1.0)))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void cosine_oppositeVectors_returnsMinusOne() {
        assertThat(CosineSimilarity.cosine(List.of(1.0, 0.0), List.of(-1.0, 0.0)))
                .isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void cosine_nullOrEmpty_returnsZero() {
        assertThat(CosineSimilarity.cosine(null, List.of(1.0))).isZero();
        assertThat(CosineSimilarity.cosine(List.of(1.0), null)).isZero();
        assertThat(CosineSimilarity.cosine(List.of(), List.of())).isZero();
    }

    @Test
    void cosine_lengthMismatch_returnsZero() {
        assertThat(CosineSimilarity.cosine(List.of(1.0, 2.0), List.of(1.0))).isZero();
    }

    @Test
    void cosine_zeroVector_returnsZero() {
        assertThat(CosineSimilarity.cosine(List.of(0.0, 0.0), List.of(1.0, 1.0))).isZero();
    }
}
