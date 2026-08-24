package org.hyland.contentlake.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.evaluation.EvaluationSample;
import org.hyland.contentlake.rag.evaluation.RagEvaluator;
import org.hyland.contentlake.rag.evaluation.RagEvaluator.EvaluationReport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * In-app RAG evaluation smoke endpoint.
 *
 * <p>{@code POST /api/rag/evaluate} runs a small caller-supplied sample set through the live
 * pipeline and returns coarse retrieval-hit and faithfulness signals - a quick sanity check, not the
 * authoritative quality gate. The external {@code content-lake-eval} harness ({@code cleval run} /
 * {@code cleval compare}) remains the RAGAS-style measurement of record and the sprint gate.</p>
 *
 * <p>Disabled by default; enable with {@code rag.evaluation.enabled=true}. Like every RAG endpoint
 * it also requires authentication, and retrieval within it is ACL-scoped to the caller.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class EvaluationController {

    private final RagEvaluator ragEvaluator;
    private final RagProperties ragProperties;

    @PostMapping("/evaluate")
    public ResponseEntity<EvaluationReport> evaluate(@RequestBody List<EvaluationSample> samples) {
        if (!ragProperties.getEvaluation().isEnabled()) {
            log.debug("Rejected /api/rag/evaluate: evaluation endpoint disabled");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (samples == null || samples.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("Running in-app evaluation over {} samples", samples.size());
        return ResponseEntity.ok(ragEvaluator.evaluate(samples));
    }
}
