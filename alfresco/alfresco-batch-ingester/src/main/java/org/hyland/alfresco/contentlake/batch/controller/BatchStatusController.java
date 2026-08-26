package org.hyland.alfresco.contentlake.batch.controller;

import lombok.RequiredArgsConstructor;
import org.hyland.alfresco.contentlake.batch.model.IngestionJob;
import org.hyland.alfresco.contentlake.batch.service.BatchIngestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;

/**
 * Compact operational status for the Alfresco batch ingester: {@code GET /api/status}.
 *
 * <p>Surfaces the most recent ingestion run (last-run timestamp, nodes discovered / indexed /
 * failed) for at-a-glance monitoring. The detailed per-job and queue view stays at
 * {@code /api/sync/status}; this endpoint reuses the same in-memory {@link IngestionJob} state.</p>
 */
@RestController
@RequestMapping("/api/status")
@RequiredArgsConstructor
public class BatchStatusController {

    private final BatchIngestionService batchIngestionService;

    @GetMapping
    public BatchStatus status() {
        IngestionJob latest = batchIngestionService.getAllJobs().values().stream()
                .max(Comparator.comparing(IngestionJob::getStartedAt))
                .orElse(null);

        if (latest == null) {
            return new BatchStatus("alfresco", "IDLE", null, null, null, 0, 0, 0);
        }
        return new BatchStatus(
                "alfresco",
                latest.getStatus().name(),
                latest.getJobId(),
                latest.getStartedAt(),
                latest.getCompletedAt(),
                latest.getDiscoveredCountValue(),
                latest.getMetadataIngestedCountValue(),
                latest.getFailedCountValue());
    }

    /** Last-run summary. {@code state} is {@code IDLE} when no run has occurred. */
    public record BatchStatus(
            String sourceType,
            String state,
            String jobId,
            Instant startedAt,
            Instant completedAt,
            int nodesDiscovered,
            int nodesIndexed,
            int nodesFailed) {
    }
}
