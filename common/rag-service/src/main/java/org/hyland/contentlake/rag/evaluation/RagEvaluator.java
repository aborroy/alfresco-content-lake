package org.hyland.contentlake.rag.evaluation;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.model.RagPromptRequest;
import org.hyland.contentlake.rag.model.RagPromptResponse;
import org.hyland.contentlake.rag.service.RagService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight in-app evaluator: runs a small set of {@link EvaluationSample}s through the live RAG
 * pipeline and reports coarse retrieval-hit and (when enabled) faithfulness signals.
 *
 * <p>This is a smoke/CI check, not the authoritative quality gate. The external
 * {@code content-lake-eval} harness (RAGAS-style, 78-question golden set, LLM-as-judge) remains the
 * measurement of record; this endpoint exists for quick in-cluster sanity checks without that
 * harness. Faithfulness is surfaced from {@link RagPromptResponse#getVerified()}, which is populated
 * by the citation verifier when {@code rag.citation.verify.enabled} is on.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvaluator {

    private final RagService ragService;

    public EvaluationReport evaluate(List<EvaluationSample> samples) {
        List<SampleResult> results = new ArrayList<>();
        int retrievalHits = 0;
        int verifiedCount = 0;

        for (EvaluationSample sample : samples) {
            RagPromptResponse response = ragService.prompt(RagPromptRequest.builder()
                    .question(sample.question())
                    .includeContext(false)
                    .build());

            List<String> matched = matchedSources(response, sample.expectedSourceIds());
            boolean hit = !matched.isEmpty();
            if (hit) {
                retrievalHits++;
            }
            if (Boolean.TRUE.equals(response.getVerified())) {
                verifiedCount++;
            }

            results.add(SampleResult.builder()
                    .question(sample.question())
                    .answer(response.getAnswer())
                    .retrievalHit(hit)
                    .matchedSources(matched)
                    .verified(response.getVerified())
                    .unsupportedClaims(response.getUnsupportedClaims())
                    .build());
        }

        int total = samples.size();
        double hitRate = total == 0 ? 0.0 : (double) retrievalHits / total;
        return EvaluationReport.builder()
                .totalSamples(total)
                .retrievalHits(retrievalHits)
                .retrievalHitRate(hitRate)
                .verifiedCount(verifiedCount)
                .results(results)
                .build();
    }

    /** Expected ids matched loosely against a source's name, node id, source id, or document id. */
    private List<String> matchedSources(RagPromptResponse response, List<String> expectedSourceIds) {
        List<String> matched = new ArrayList<>();
        if (response.getSources() == null || expectedSourceIds == null) {
            return matched;
        }
        for (String expected : expectedSourceIds) {
            if (expected == null || expected.isBlank()) {
                continue;
            }
            boolean present = response.getSources().stream().anyMatch(s ->
                    equalsIgnoreCaseSafe(expected, s.getName())
                            || equalsIgnoreCaseSafe(expected, s.getNodeId())
                            || equalsIgnoreCaseSafe(expected, s.getSourceId())
                            || equalsIgnoreCaseSafe(expected, s.getDocumentId()));
            if (present) {
                matched.add(expected);
            }
        }
        return matched;
    }

    private static boolean equalsIgnoreCaseSafe(String expected, String actual) {
        return actual != null && expected.equalsIgnoreCase(actual);
    }

    /** Aggregate evaluation report. */
    @Builder
    public record EvaluationReport(int totalSamples, int retrievalHits, double retrievalHitRate,
                                   int verifiedCount, List<SampleResult> results) {
    }

    /** Per-sample outcome. */
    @Builder
    public record SampleResult(String question, String answer, boolean retrievalHit,
                               List<String> matchedSources, Boolean verified,
                               List<String> unsupportedClaims) {
    }
}
