package org.alfresco.contentlake.nuxeo.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.alfresco.contentlake.nuxeo.batch.model.IngestionJob;
import org.alfresco.contentlake.nuxeo.batch.model.NuxeoSyncRequest;
import org.alfresco.contentlake.service.NodeSyncService;
import org.alfresco.contentlake.spi.SourceNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class NuxeoBatchIngestionService {

    private final NuxeoDiscoveryService discoveryService;
    private final NodeSyncService nodeSyncService;
    private final Executor batchExecutor;
    private final Map<String, IngestionJob> jobsById = new ConcurrentHashMap<>();

    public NuxeoBatchIngestionService(NuxeoDiscoveryService discoveryService,
                                      NodeSyncService nodeSyncService,
                                      @Qualifier("nuxeoBatchIngestionExecutor") Executor batchExecutor) {
        this.discoveryService = discoveryService;
        this.nodeSyncService = nodeSyncService;
        this.batchExecutor = batchExecutor;
    }

    public IngestionJob startConfiguredSync() {
        IngestionJob job = createJob("configured sync");
        CompletableFuture.runAsync(() -> runSync(job, discoveryService.discoverFromConfig()), batchExecutor);
        return job;
    }

    public IngestionJob startBatchSync(NuxeoSyncRequest request) {
        IngestionJob job = createJob("batch sync");
        CompletableFuture.runAsync(() -> runSync(job, discoveryService.discover(request)), batchExecutor);
        return job;
    }

    public IngestionJob getJob(String jobId) {
        return jobsById.get(jobId);
    }

    public Map<String, IngestionJob> getAllJobs() {
        return jobsById;
    }

    private IngestionJob createJob(String label) {
        String jobId = UUID.randomUUID().toString();
        IngestionJob job = new IngestionJob(jobId);
        jobsById.put(jobId, job);
        log.info("Starting Nuxeo {} job {}", label, jobId);
        return job;
    }

    private void runSync(IngestionJob job, List<SourceNode> nodes) {
        try {
            nodes.forEach(node -> syncNode(node, job));
            job.complete();
            log.info("Nuxeo sync job {} completed. Discovered: {}, Synced: {}, Skipped: {}, Failed: {}",
                    job.getJobId(),
                    job.getDiscoveredCountValue(),
                    job.getSyncedCountValue(),
                    job.getSkippedCountValue(),
                    job.getFailedCountValue());
        } catch (Exception e) {
            job.fail();
            log.error("Nuxeo sync job {} failed", job.getJobId(), e);
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
                    metadata.documentPath()
            );
            job.incrementSynced();
        } catch (Exception e) {
            job.incrementFailed();
            log.error("Failed to sync Nuxeo node {}", node.nodeId(), e);
        }
    }
}
