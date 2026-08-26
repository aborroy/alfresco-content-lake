package org.hyland.filesystem.contentlake.batch.controller;

import lombok.RequiredArgsConstructor;
import org.hyland.filesystem.contentlake.batch.model.IngestionJob;
import org.hyland.filesystem.contentlake.batch.service.FileSystemBatchIngestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller exposing filesystem batch synchronization endpoints.
 */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final FileSystemBatchIngestionService batchIngestionService;

    /** Starts a batch sync of the configured filesystem root. */
    @PostMapping("/configured")
    public IngestionJob startConfiguredSync() {
        return batchIngestionService.startConfiguredSync();
    }

    /** Retrieves the status of a specific ingestion job. */
    @GetMapping("/status/{jobId}")
    public IngestionJob getJobStatus(@PathVariable String jobId) {
        return batchIngestionService.getJob(jobId);
    }

    /** Returns all known jobs keyed by identifier. */
    @GetMapping("/status")
    public Map<String, IngestionJob> getOverallStatus() {
        return batchIngestionService.getAllJobs();
    }
}
