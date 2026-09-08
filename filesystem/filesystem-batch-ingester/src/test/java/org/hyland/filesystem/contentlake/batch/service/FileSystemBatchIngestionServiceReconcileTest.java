package org.hyland.filesystem.contentlake.batch.service;

import org.hyland.contentlake.service.DiscoveryOutcome;
import org.hyland.contentlake.service.IndexReconciliationService;
import org.hyland.contentlake.service.NodeSyncService;
import org.hyland.contentlake.service.SeenSet;
import org.hyland.contentlake.spi.SourceNode;
import org.hyland.filesystem.contentlake.batch.config.FilesystemBatchProperties;
import org.hyland.filesystem.contentlake.batch.model.IngestionJob;
import org.hyland.filesystem.contentlake.client.FileSystemSourceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reconciliation coverage for the filesystem source (#115).
 *
 * <p>This is the filesystem source's whole reconciliation coverage, deliberately. The end-to-end suite
 * exercises the other two sources by stopping their live ingester and re-syncing, but the filesystem
 * source has <em>no</em> live ingester, so "with the live ingester stopped" is vacuous there: a
 * filesystem batch sync is the only path that ever writes, and reconciliation is the only path that
 * ever deletes.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileSystemBatchIngestionServiceReconcileTest {

    private static final String ROOT = "/data/filesystem";

    @Mock
    private FileSystemDiscoveryService discoveryService;
    @Mock
    private NodeSyncService nodeSyncService;
    @Mock
    private IndexReconciliationService reconciliationService;
    @Mock
    private FileSystemSourceClient sourceClient;

    private FilesystemBatchProperties props;
    private FileSystemBatchIngestionService service;

    /** Synchronous, so a test can assert on job state once the call returns. */
    private final Executor syncExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        props = new FilesystemBatchProperties();
        service = new FileSystemBatchIngestionService(discoveryService, nodeSyncService, syncExecutor,
                reconciliationService, sourceClient, props);

        when(sourceClient.getSourceId()).thenReturn("filesystem");
        when(nodeSyncService.contentLakePathPrefix(eq("filesystem"), any()))
                .thenReturn("/filesystem-sync/filesystem" + ROOT);
        when(nodeSyncService.ingestMetadata(any())).thenReturn(new NodeSyncService.SyncResult(
                "hxpr-1", "node-1", "text/plain", "a.txt", ROOT, false, Map.of()));
    }

    @Test
    void doesNotSweepWhenReconciliationIsDisabled() {
        // The application default. A sweep deletes documents, so it is opt-in.
        assertThat(props.getReconcile().isEnabled()).isFalse();
        when(discoveryService.discoverTallied()).thenReturn(discovery(node(ROOT + "/a.txt")));

        IngestionJob job = service.startConfiguredSync();

        assertThat(job.getStatus()).isEqualTo(IngestionJob.JobStatus.COMPLETED);
        verify(reconciliationService, never()).reconcile(any(), any(), anyInt(), any(), any());
        assertThat(job.getReconciliation()).isNull();
    }

    @Test
    void sweepsWithTheDiscoveredIdsWhenEnabled() {
        props.getReconcile().setEnabled(true);
        when(discoveryService.discoverTallied())
                .thenReturn(discovery(node(ROOT + "/a.txt"), node(ROOT + "/b.txt")));
        when(reconciliationService.reconcile(any(), any(), anyInt(), any(), any()))
                .thenReturn(report(1));

        IngestionJob job = service.startConfiguredSync();

        ArgumentCaptor<SeenSet> seenCaptor = ArgumentCaptor.forClass(SeenSet.class);
        verify(reconciliationService).reconcile(seenCaptor.capture(), any(), anyInt(), any(), any());

        SeenSet seen = seenCaptor.getValue();
        assertThat(seen.contains(ROOT + "/a.txt")).isTrue();
        assertThat(seen.contains(ROOT + "/b.txt")).isTrue();
        assertThat(seen.contains(ROOT + "/never-existed.txt")).isFalse();
        // Reported on the job, because the sync status API is what an operator reads.
        assertThat(job.getReconciliation().deleted()).isEqualTo(1);
    }

    @Test
    void passesTheNodeFailureCountThrough_soAFailedNodeBlocksTheSweep() {
        props.getReconcile().setEnabled(true);
        when(discoveryService.discoverTallied()).thenReturn(discovery(node(ROOT + "/a.txt")));
        when(nodeSyncService.ingestMetadata(any())).thenThrow(new RuntimeException("read failed"));
        when(reconciliationService.reconcile(any(), any(), anyInt(), any(), any()))
                .thenReturn(report(0));

        service.startConfiguredSync();

        // The sweep itself decides to skip; what matters here is that the count reaches it.
        verify(reconciliationService).reconcile(any(), any(), eq(1), any(), any());
    }

    @Test
    void scopesTheSweepToTheIndexedPathPrefix_notTheRawFilesystemPath() {
        // cin_paths holds the hxpr path, so a predicate built from the raw mounted path would match
        // nothing, delete nothing, and look like a working feature.
        props.getReconcile().setEnabled(true);
        when(discoveryService.discoverTallied()).thenReturn(discovery(node(ROOT + "/a.txt")));
        when(reconciliationService.reconcile(any(), any(), anyInt(), any(), any()))
                .thenReturn(report(0));

        service.startConfiguredSync();

        verify(nodeSyncService).contentLakePathPrefix("filesystem", ROOT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<org.hyland.contentlake.model.HxprDocument>> predicateCaptor =
                ArgumentCaptor.forClass(Predicate.class);
        verify(reconciliationService).reconcile(any(), any(), anyInt(), predicateCaptor.capture(), any());

        org.hyland.contentlake.model.HxprDocument indexed = new org.hyland.contentlake.model.HxprDocument();
        indexed.setCinPaths(List.of("/filesystem-sync/filesystem" + ROOT + "/a.txt"));
        assertThat(predicateCaptor.getValue().test(indexed)).isTrue();

        org.hyland.contentlake.model.HxprDocument elsewhere = new org.hyland.contentlake.model.HxprDocument();
        elsewhere.setCinPaths(List.of("/alfresco-sync/repo/Sites/x.txt"));
        assertThat(predicateCaptor.getValue().test(elsewhere)).isFalse();
    }

    @Test
    void aSweepFailureDoesNotFailTheIngestion() {
        props.getReconcile().setEnabled(true);
        when(discoveryService.discoverTallied()).thenReturn(discovery(node(ROOT + "/a.txt")));
        when(reconciliationService.reconcile(any(), any(), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("hxpr unavailable"));

        IngestionJob job = service.startConfiguredSync();

        // Ingestion succeeded; the sweep is an optional phase that runs again on the next sync.
        assertThat(job.getStatus()).isEqualTo(IngestionJob.JobStatus.COMPLETED);
        assertThat(job.getReconciliation()).isNull();
    }

    @Test
    void aDiscoveryFailureFailsTheJobAndSkipsTheSweepEntirely() {
        props.getReconcile().setEnabled(true);
        when(discoveryService.discoverTallied()).thenThrow(new RuntimeException("root unreadable"));

        IngestionJob job = service.startConfiguredSync();

        assertThat(job.getStatus()).isEqualTo(IngestionJob.JobStatus.FAILED);
        verify(reconciliationService, never()).reconcile(any(), any(), anyInt(), any(), any());
    }

    @Test
    void sizesTheSeenSetFromConfiguration() {
        props.getReconcile().setEnabled(true);
        props.getReconcile().setMaxSeenIds(1);
        when(discoveryService.discoverTallied())
                .thenReturn(discovery(node(ROOT + "/a.txt"), node(ROOT + "/b.txt")));
        when(reconciliationService.reconcile(any(), any(), anyInt(), any(), any()))
                .thenReturn(report(0));

        service.startConfiguredSync();

        ArgumentCaptor<SeenSet> seenCaptor = ArgumentCaptor.forClass(SeenSet.class);
        verify(reconciliationService).reconcile(seenCaptor.capture(), any(), anyInt(), any(), any());
        // Overflow is what the sweep checks to refuse deleting on an incomplete record.
        assertThat(seenCaptor.getValue().overflowed()).isTrue();
    }

    private static IndexReconciliationService.Report report(int deleted) {
        return new IndexReconciliationService.Report(
                IndexReconciliationService.Status.COMPLETED, 2, 2, deleted, deleted, 0, 0.5, "ok");
    }

    private static FileSystemDiscoveryService.FileSystemDiscovery discovery(SourceNode... nodes) {
        return new FileSystemDiscoveryService.FileSystemDiscovery(
                List.of(nodes), DiscoveryOutcome.complete(List.of(ROOT)));
    }

    /** Filesystem node ids are absolute paths, unlike the opaque UUIDs of the other sources. */
    private static SourceNode node(String path) {
        return new SourceNode(path, "filesystem", "filesystem",
                path.substring(path.lastIndexOf('/') + 1), ROOT, "text/plain",
                OffsetDateTime.parse("2026-09-01T10:00:00Z"), false,
                Set.of("__Everyone__"), Set.of(), Map.of());
    }
}
