package org.hyland.contentlake.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprTermsAggregationResult;
import org.hyland.contentlake.rag.health.HxprHealthIndicator;
import org.hyland.contentlake.rag.health.ModelRunnerHealthIndicator;
import org.hyland.contentlake.rag.model.StatusResponse;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational visibility endpoint: {@code GET /api/status}.
 *
 * <p>Aggregates hxpr connectivity, per-source indexed document counts, and model-runner reachability
 * into one snapshot. Reuses the actuator {@link HxprHealthIndicator} / {@link ModelRunnerHealthIndicator}
 * for the connectivity probes so the status view and {@code /actuator/health} cannot disagree.</p>
 *
 * <p>Authenticated like every other {@code /api/**} route (per-source counts are information
 * disclosure); the security chain's {@code anyRequest().authenticated()} covers it.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/status")
@RequiredArgsConstructor
public class StatusController {

    /** Aggregation field: per-repository counts keyed by {@code "<type>:<id>"}. */
    private static final String SOURCE_ID_FIELD = "cin_sourceId";
    private static final int MAX_SOURCE_BUCKETS = 100;

    private final HxprService hxprService;
    private final HxprHealthIndicator hxprHealthIndicator;
    private final ModelRunnerHealthIndicator modelRunnerHealthIndicator;

    @GetMapping
    public StatusResponse status() {
        Status hxprStatus = hxprHealthIndicator.health().getStatus();
        Health modelHealth = modelRunnerHealthIndicator.health();

        Map<String, Long> sourceCounts = new LinkedHashMap<>();
        long total = 0;
        if (Status.UP.equals(hxprStatus)) {
            try {
                HxprTermsAggregationResult agg =
                        hxprService.termsAggregation(null, SOURCE_ID_FIELD, null, MAX_SOURCE_BUCKETS);
                if (agg != null && agg.getAggregationsBuckets() != null) {
                    for (HxprTermsAggregationResult.Bucket bucket : agg.getAggregationsBuckets()) {
                        if (bucket.getKey() == null) {
                            continue;
                        }
                        sourceCounts.put(bucket.getKey(), bucket.getDocCount());
                        total += bucket.getDocCount();
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to aggregate per-source document counts: {}", e.getMessage());
            }
        }

        return StatusResponse.builder()
                .hxprStatus(hxprStatus.getCode())
                .totalDocuments(total)
                .sourceCounts(sourceCounts)
                .embeddingModel(StatusResponse.ModelRunnerStatus.builder()
                        .status(modelHealth.getStatus().getCode())
                        .url((String) modelHealth.getDetails().get("url"))
                        .build())
                .build();
    }
}
