package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreThresholdRetrievalGraderTest {

    private RagProperties properties;
    private ScoreThresholdRetrievalGrader grader;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        grader = new ScoreThresholdRetrievalGrader(properties);
    }

    private static SearchHit hit(double score) {
        return SearchHit.builder().score(score).chunkText("chunk").build();
    }

    @Test
    void grade_emptyOrNullHits_isWeak() {
        assertThat(grader.grade("q", List.of())).isEqualTo(RetrievalGrader.Verdict.WEAK);
        assertThat(grader.grade("q", null)).isEqualTo(RetrievalGrader.Verdict.WEAK);
    }

    @Test
    void grade_defaultThresholdOfZero_isInert() {
        // The default must not change behaviour even once the gate is switched on, because a threshold
        // is only meaningful against the configured fusion strategy's score scale.
        assertThat(grader.grade("q", List.of(hit(0.0), hit(0.0))))
                .isEqualTo(RetrievalGrader.Verdict.RELEVANT);
    }

    @Test
    void grade_enoughHitsClearTheThreshold_isRelevant() {
        properties.getRetrievalGrading().setMinScore(0.4);

        assertThat(grader.grade("q", List.of(hit(0.5), hit(0.1))))
                .isEqualTo(RetrievalGrader.Verdict.RELEVANT);
    }

    @Test
    void grade_noHitClearsTheThreshold_isWeak() {
        properties.getRetrievalGrading().setMinScore(0.4);

        assertThat(grader.grade("q", List.of(hit(0.3), hit(0.1))))
                .isEqualTo(RetrievalGrader.Verdict.WEAK);
    }

    @Test
    void grade_thresholdIsInclusive() {
        properties.getRetrievalGrading().setMinScore(0.4);

        assertThat(grader.grade("q", List.of(hit(0.4)))).isEqualTo(RetrievalGrader.Verdict.RELEVANT);
    }

    @Test
    void grade_minHits_requiresThatManyClearingHits() {
        properties.getRetrievalGrading().setMinScore(0.4);
        properties.getRetrievalGrading().setMinHits(2);

        assertThat(grader.grade("q", List.of(hit(0.9), hit(0.1))))
                .isEqualTo(RetrievalGrader.Verdict.WEAK);
        assertThat(grader.grade("q", List.of(hit(0.9), hit(0.5), hit(0.1))))
                .isEqualTo(RetrievalGrader.Verdict.RELEVANT);
    }

    @Test
    void grade_minHitsBelowOne_isTreatedAsOne() {
        properties.getRetrievalGrading().setMinScore(0.4);
        properties.getRetrievalGrading().setMinHits(0);

        assertThat(grader.grade("q", List.of(hit(0.1)))).isEqualTo(RetrievalGrader.Verdict.WEAK);
        assertThat(grader.grade("q", List.of(hit(0.5)))).isEqualTo(RetrievalGrader.Verdict.RELEVANT);
    }
}
