package org.hyland.contentlake.rag.evaluation;

import org.hyland.contentlake.rag.model.RagPromptRequest;
import org.hyland.contentlake.rag.model.RagPromptResponse;
import org.hyland.contentlake.rag.model.RagPromptResponse.Source;
import org.hyland.contentlake.rag.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagEvaluatorTest {

    @Mock
    RagService ragService;

    private RagEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new RagEvaluator(ragService);
    }

    private static RagPromptResponse responseWithSource(String name, Boolean verified) {
        return RagPromptResponse.builder()
                .answer("an answer")
                .sources(List.of(Source.builder().name(name).build()))
                .verified(verified)
                .build();
    }

    @Test
    void scoresRetrievalHitsAndVerification() {
        when(ragService.prompt(any(RagPromptRequest.class)))
                .thenReturn(responseWithSource("policy.txt", Boolean.TRUE))   // hit + verified
                .thenReturn(responseWithSource("other.txt", null));           // miss + unknown

        RagEvaluator.EvaluationReport report = evaluator.evaluate(List.of(
                new EvaluationSample("q1", "a1", List.of("policy.txt")),
                new EvaluationSample("q2", "a2", List.of("expected.txt"))));

        assertThat(report.totalSamples()).isEqualTo(2);
        assertThat(report.retrievalHits()).isEqualTo(1);
        assertThat(report.retrievalHitRate()).isCloseTo(0.5, within(1e-9));
        assertThat(report.verifiedCount()).isEqualTo(1);
        assertThat(report.results()).hasSize(2);
        assertThat(report.results().get(0).retrievalHit()).isTrue();
        assertThat(report.results().get(0).matchedSources()).containsExactly("policy.txt");
        assertThat(report.results().get(1).retrievalHit()).isFalse();
    }

    @Test
    void matchesSourceIdCaseInsensitively() {
        when(ragService.prompt(any(RagPromptRequest.class)))
                .thenReturn(responseWithSource("Policy.TXT", null));

        RagEvaluator.EvaluationReport report = evaluator.evaluate(List.of(
                new EvaluationSample("q", "a", List.of("policy.txt"))));

        assertThat(report.retrievalHits()).isEqualTo(1);
    }
}
