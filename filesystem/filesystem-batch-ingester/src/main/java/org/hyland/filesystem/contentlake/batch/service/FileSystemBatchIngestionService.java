package org.hyland.filesystem.contentlake.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.service.NodeSyncService;
import org.hyland.contentlake.spi.SourceNode;
import org.hyland.filesystem.contentlake.batch.model.IngestionJob;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Orchestrates asynchronous filesystem batch ingestion jobs and tracks their execution state.
 */
@Slf4j
@Service
public class FileSystemBatchIngestionService {

    private static final int MAX_RETAINED_JOBS = 100;

    private final FileSystemDiscoveryService discoveryService;
    private final NodeSyncService nodeSyncService;
    private final Executor batchExecutor;
    private final Map<String, IngestionJob> jobsById = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_RETAINED_JOBS, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, IngestionJob> eldest) {
                    return size() > MAX_RETAINED_JOBS;
                }
            });

    public FileSystemBatchIngestionService(FileSystemDiscoveryService discoveryService,
                                           NodeSyncService nodeSyncService,
                                           @Qualifier("filesystemBatchIngestionExecutor") Executor batchExecutor) {
        this.discoveryService = discoveryService;
        this.nodeSyncService = nodeSyncService;
        this.batchExecutor = batchExecutor;
    }

    public IngestionJob startConfiguredSync() {
        IngestionJob job = createJob();
        CompletableFuture.runAsync(() -> runJob(job), batchExecutor);
        return job;
    }

    public IngestionJob getJob(String jobId) {
        return jobsById.get(jobId);
    }

    public Map<String, IngestionJob> getAllJobs() {
        synchronized (jobsById) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(jobsById));
        }
    }

    private IngestionJob createJob() {
        String jobId = UUID.randomUUID().toString();
        IngestionJob job = new IngestionJob(jobId);
        jobsById.put(jobId, job);
        log.info("Starting filesystem batch job {}", jobId);
        return job;
    }

    private void runJob(IngestionJob job) {
        try {
            List<SourceNode> nodes = discoveryService.discover();
            nodes.forEach(node -> syncNode(node, job));
            job.complete();
            log.info("Filesystem sync job {} completed. Discovered: {}, Synced: {}, Skipped: {}, Failed: {}",
                    job.getJobId(),
                    job.getDiscoveredCountValue(),
                    job.getSyncedCountValue(),
                    job.getSkippedCountValue(),
                    job.getFailedCountValue());
        } catch (Exception e) {
            job.fail();
            log.error("Filesystem batch job {} failed", job.getJobId(), e);
        }
    }

    private void syncNode(SourceNode node, IngestionJob job) {
        job.incrementDiscovered();
        try {
            NodeSyncService.SyncResult metadata = nodeSyncService.ingestMetadata(node);
            if (metadata.skipped()) {
                job.incrementSkipped();
                return;
            }
            nodeSyncService.processContent(
                    metadata.hxprDocId(),
                    metadata.ingestProperties(),
                    metadata.nodeId(),
                    metadata.mimeType(),
                    metadata.documentName(),
                    metadata.documentPath());
            job.incrementSynced();
        } catch (Exception e) {
            job.incrementFailed();
            log.error("Failed to sync filesystem node {}", node.nodeId(), e);
        }
    }
}
