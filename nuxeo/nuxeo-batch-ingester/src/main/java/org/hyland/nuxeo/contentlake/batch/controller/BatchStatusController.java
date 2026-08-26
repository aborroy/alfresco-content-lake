package org.hyland.nuxeo.contentlake.batch.controller;

import lombok.RequiredArgsConstructor;
import org.hyland.nuxeo.contentlake.batch.model.IngestionJob;
import org.hyland.nuxeo.contentlake.batch.service.NuxeoBatchIngestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;

/**
 * Compact operational status for the Nuxeo batch ingester: {@code GET /api/status}.
 *
 * <p>Surfaces the most recent ingestion run (last-run timestamp, nodes discovered / indexed /
 * skipped / failed) for at-a-glance monitoring. The detailed per-job view stays at
 * {@code /api/sync/status}; this endpoint reuses the same in-memory {@link IngestionJob} state.</p>
 */
@RestController
@RequestMapping("/api/status")
@RequiredArgsConstructor
public class BatchStatusController {

    private final NuxeoBatchIngestionService batchIngestionService;

    @GetMapping
    public BatchStatus status() {
        IngestionJob latest = batchIngestionService.getAllJobs().values().stream()
                .max(Comparator.comparing(IngestionJob::getStartedAt))
                .orElse(null);

        if (latest == null) {
            return new BatchStatus("nuxeo", "IDLE", null, null, null, 0, 0, 0, 0);
        }
        return new BatchStatus(
                "nuxeo",
                latest.getStatus().name(),
                latest.getJobId(),
                latest.getStartedAt(),
                latest.getCompletedAt(),
                latest.getDiscoveredCountValue(),
                latest.getSyncedCountValue(),
                latest.getSkippedCountValue(),
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
            int nodesSkipped,
            int nodesFailed) {
    }
}
