package org.hyland.filesystem.contentlake.batch.controller;

import lombok.RequiredArgsConstructor;
import org.hyland.filesystem.contentlake.batch.model.IngestionJob;
import org.hyland.filesystem.contentlake.batch.service.FileSystemBatchIngestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;

/**
 * Compact operational status for the filesystem batch ingester: {@code GET /api/status}.
 *
 * <p>Surfaces the most recent ingestion run (last-run timestamp, nodes discovered / indexed /
 * skipped / failed). The detailed per-job view stays at {@code /api/sync/status}.</p>
 */
@RestController
@RequestMapping("/api/status")
@RequiredArgsConstructor
public class BatchStatusController {

    private final FileSystemBatchIngestionService batchIngestionService;

    @GetMapping
    public BatchStatus status() {
        IngestionJob latest = batchIngestionService.getAllJobs().values().stream()
                .max(Comparator.comparing(IngestionJob::getStartedAt))
                .orElse(null);

        if (latest == null) {
            return new BatchStatus("filesystem", "IDLE", null, null, null, 0, 0, 0, 0);
        }
        return new BatchStatus(
                "filesystem",
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
