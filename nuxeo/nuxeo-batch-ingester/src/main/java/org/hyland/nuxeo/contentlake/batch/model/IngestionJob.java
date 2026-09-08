package org.hyland.nuxeo.contentlake.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.hyland.contentlake.service.IndexReconciliationService;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@RequiredArgsConstructor
public class IngestionJob {

    public enum JobStatus { RUNNING, COMPLETED, FAILED }

    private static final String SOURCE_TYPE = "nuxeo";

    private final String jobId;

    private volatile JobStatus status = JobStatus.RUNNING;
    private final Instant startedAt = Instant.now();
    private volatile Instant completedAt;

    private final String sourceType = SOURCE_TYPE;

    @JsonIgnore
    private final AtomicInteger discoveredCount = new AtomicInteger(0);

    @JsonIgnore
    private final AtomicInteger syncedCount = new AtomicInteger(0);

    @JsonIgnore
    private final AtomicInteger skippedCount = new AtomicInteger(0);

    @JsonIgnore
    private final AtomicInteger failedCount = new AtomicInteger(0);

    public void incrementDiscovered() {
        discoveredCount.incrementAndGet();
    }

    public void incrementSynced() {
        syncedCount.incrementAndGet();
    }

    public void incrementSkipped() {
        skippedCount.incrementAndGet();
    }

    public void incrementFailed() {
        failedCount.incrementAndGet();
    }

    /**
     * What the post-sync reconciliation sweep did, or {@code null} when it did not run (#115).
     *
     * <p>Reported on the job because the sync status API is what an operator and the end-to-end suite
     * read; a sweep result that is not here is invisible to both.</p>
     */
    @JsonProperty("reconciliation")
    private volatile IndexReconciliationService.Report reconciliation;

    public void recordReconciliation(IndexReconciliationService.Report report) {
        this.reconciliation = report;
    }

    public void complete() {
        status = JobStatus.COMPLETED;
        completedAt = Instant.now();
    }

    public void fail() {
        status = JobStatus.FAILED;
        completedAt = Instant.now();
    }

    @JsonProperty("discoveredCount")
    public int getDiscoveredCountValue() {
        return discoveredCount.get();
    }

    @JsonProperty("syncedCount")
    public int getSyncedCountValue() {
        return syncedCount.get();
    }

    @JsonProperty("skippedCount")
    public int getSkippedCountValue() {
        return skippedCount.get();
    }

    @JsonProperty("failedCount")
    public int getFailedCountValue() {
        return failedCount.get();
    }
}
