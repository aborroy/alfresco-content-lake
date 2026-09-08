package org.hyland.contentlake.service;

import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.spi.ContentSourceClient;
import org.hyland.contentlake.spi.SourceTombstone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the sweep #115 adds, and specifically its guards. A sweep that gets its input wrong empties
 * the index, so most of these assert that nothing is deleted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexReconciliationServiceTest {

    private static final String PREFIX = "/alfresco-sync/repo-uuid/Sites/marketing";

    @Mock
    private HxprService hxprService;
    @Mock
    private NodeSyncService nodeSyncService;
    @Mock
    private ContentSourceClient sourceClient;

    private IndexReconciliationService service;
    private Predicate<HxprDocument> inScope;

    @BeforeEach
    void setUp() {
        service = new IndexReconciliationService(hxprService, nodeSyncService, sourceClient);
        inScope = IndexReconciliationService.underAnyPath(List.of(PREFIX));
        when(sourceClient.getSourceType()).thenReturn("alfresco");
        when(sourceClient.getSourceId()).thenReturn("repo-uuid");
        when(nodeSyncService.delete(any())).thenReturn(NodeSyncService.DeleteOutcome.DELETED);
    }

    @Test
    void deletesOnlyDocumentsDiscoveryDidNotSee() {
        indexHolds(doc("kept", PREFIX + "/kept.txt"), doc("gone", PREFIX + "/gone.txt"));

        IndexReconciliationService.Report report =
                service.reconcile(seen("kept"), complete(), 0, inScope, enabled(1.0));

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.COMPLETED);
        assertThat(report.deleted()).isEqualTo(1);

        ArgumentCaptor<SourceTombstone> captor = ArgumentCaptor.forClass(SourceTombstone.class);
        verify(nodeSyncService).delete(captor.capture());
        assertThat(captor.getValue().nodeId()).isEqualTo("gone");
        assertThat(captor.getValue().reason())
                .isEqualTo(SourceTombstone.Reason.MISSING_AT_RECONCILE);
    }

    @Test
    void doesNothingWhenDisabled() {
        indexHolds(doc("gone", PREFIX + "/gone.txt"));

        IndexReconciliationService.Report report =
                service.reconcile(seen("kept"), complete(), 0, inScope, ReconcileConfig.disabled());

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.DISABLED);
        verifyNothingDeleted();
    }

    @Test
    void deletesNothingWhenDiscoveryReportsItselfIncomplete() {
        // The acceptance criterion: a discovery pass that fails partway performs no deletions.
        indexHolds(doc("gone", PREFIX + "/gone.txt"));

        IndexReconciliationService.Report report = service.reconcile(
                seen("kept"),
                DiscoveryOutcome.incomplete(List.of(PREFIX), List.of("search index returned 0 descendants")),
                0, inScope, enabled(1.0));

        assertThat(report.status())
                .isEqualTo(IndexReconciliationService.Status.SKIPPED_INCOMPLETE_DISCOVERY);
        assertThat(report.detail()).contains("0 descendants");
        verifyNothingDeleted();
    }

    @Test
    void deletesNothingWhenAnyNodeFailedDuringTheSync() {
        // A failed node may or may not have been enumerated, so the seen set is not trustworthy.
        indexHolds(doc("gone", PREFIX + "/gone.txt"));

        IndexReconciliationService.Report report =
                service.reconcile(seen("kept"), complete(), 1, inScope, enabled(1.0));

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.SKIPPED_NODE_FAILURES);
        verifyNothingDeleted();
    }

    @Test
    void deletesNothingWhenDiscoverySawNoNodes() {
        // Zero nodes is exactly what a broken discovery returns.
        indexHolds(doc("gone", PREFIX + "/gone.txt"));

        IndexReconciliationService.Report report =
                service.reconcile(new SeenSet(100), complete(), 0, inScope, enabled(1.0));

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.SKIPPED_EMPTY_DISCOVERY);
        verifyNothingDeleted();
    }

    @Test
    void abortsWhenTheSeenSetOverflowed() {
        // Past its bound the set is incomplete, so ids discovery did see would look missing.
        SeenSet overflowed = new SeenSet(1);
        overflowed.add("a");
        overflowed.add("b");
        assertThat(overflowed.overflowed()).isTrue();
        indexHolds(doc("gone", PREFIX + "/gone.txt"));

        IndexReconciliationService.Report report =
                service.reconcile(overflowed, complete(), 0, inScope, enabled(1.0));

        assertThat(report.status())
                .isEqualTo(IndexReconciliationService.Status.ABORTED_SEEN_SET_OVERFLOW);
        verifyNothingDeleted();
    }

    @Test
    void abortsWhenTheDeletionRatioIsExceeded() {
        // Three of four in-scope documents unseen: the signature of a partial discovery that did not
        // report itself as one.
        indexHolds(doc("kept", PREFIX + "/kept.txt"),
                doc("g1", PREFIX + "/g1.txt"), doc("g2", PREFIX + "/g2.txt"), doc("g3", PREFIX + "/g3.txt"));

        IndexReconciliationService.Report report =
                service.reconcile(seen("kept"), complete(), 0, inScope, enabled(0.10));

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.ABORTED_RATIO);
        assertThat(report.candidates()).isEqualTo(3);
        assertThat(report.deleted()).isZero();
        assertThat(report.ratio()).isEqualTo(0.75);
        assertThat(report.detail()).contains("max-delete-ratio");
        verifyNothingDeleted();
    }

    @Test
    void abortsWhenTheAbsoluteDeletionCapIsExceeded() {
        indexHolds(doc("kept", PREFIX + "/kept.txt"),
                doc("g1", PREFIX + "/g1.txt"), doc("g2", PREFIX + "/g2.txt"));

        IndexReconciliationService.Report report = service.reconcile(seen("kept"), complete(), 0, inScope,
                new ReconcileConfig(true, 1.0, 1, 200_000, 200));

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.ABORTED_ABSOLUTE_CAP);
        assertThat(report.detail()).contains("max-deletes");
        verifyNothingDeleted();
    }

    @Test
    void abortsWhenTheIndexScanFails() {
        // A partial scan cannot distinguish a missing document from an unscanned one.
        when(hxprService.forEachDocumentOfSource(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("hxpr unavailable"));

        IndexReconciliationService.Report report =
                service.reconcile(seen("kept"), complete(), 0, inScope, enabled(1.0));

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.ABORTED_SCAN_FAILED);
        verifyNothingDeleted();
    }

    @Test
    void leavesOutOfScopeDocumentsAlone() {
        // Another sync owns them; deleting them here would make two scopes fight over the index.
        indexHolds(doc("kept", PREFIX + "/kept.txt"),
                doc("elsewhere", "/alfresco-sync/repo-uuid/Sites/finance/other.txt"));

        IndexReconciliationService.Report report =
                service.reconcile(seen("kept"), complete(), 0, inScope, enabled(1.0));

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.COMPLETED);
        assertThat(report.indexed()).isEqualTo(2);
        assertThat(report.inScope()).isEqualTo(1);
        verifyNothingDeleted();
    }

    @Test
    void neverDeletesAnEmbeddingChildAsIfItWereADocument() {
        // A SysEmbeddings child carries no cin_sourceId so it should never be scanned, but deleting
        // one as a parent would silently strip a document's vectors.
        HxprDocument child = doc("gone", PREFIX + "/gone.txt");
        child.setSysName("_e_ai-mxbai-embed-large");
        indexHolds(doc("kept", PREFIX + "/kept.txt"), child);

        IndexReconciliationService.Report report =
                service.reconcile(seen("kept"), complete(), 0, inScope, enabled(1.0));

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.COMPLETED);
        verifyNothingDeleted();
    }

    @Test
    void neverDeletesADocumentWithNoPaths() {
        // Fail-safe direction: a document the scope predicate cannot place is left alone.
        indexHolds(doc("kept", PREFIX + "/kept.txt"), doc("pathless", (String[]) null));

        IndexReconciliationService.Report report =
                service.reconcile(seen("kept"), complete(), 0, inScope, enabled(1.0));

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.COMPLETED);
        verifyNothingDeleted();
    }

    @Test
    void reportsNothingToDeleteWhenTheIndexMatchesTheSource() {
        indexHolds(doc("a", PREFIX + "/a.txt"), doc("b", PREFIX + "/b.txt"));

        IndexReconciliationService.Report report =
                service.reconcile(seen("a", "b"), complete(), 0, inScope, enabled(1.0));

        assertThat(report.status()).isEqualTo(IndexReconciliationService.Status.COMPLETED);
        assertThat(report.candidates()).isZero();
        verifyNothingDeleted();
    }

    @Test
    void countsAFailedDeleteSeparatelyFromASuccessfulOne() {
        indexHolds(doc("kept", PREFIX + "/kept.txt"), doc("gone", PREFIX + "/gone.txt"));
        when(nodeSyncService.delete(any())).thenReturn(NodeSyncService.DeleteOutcome.FAILED);

        IndexReconciliationService.Report report =
                service.reconcile(seen("kept"), complete(), 0, inScope, enabled(1.0));

        assertThat(report.deleted()).isZero();
        assertThat(report.failed()).isEqualTo(1);
    }

    @Test
    void treatsAConcurrentDeleteAsSuccess() {
        indexHolds(doc("kept", PREFIX + "/kept.txt"), doc("gone", PREFIX + "/gone.txt"));
        when(nodeSyncService.delete(any())).thenReturn(NodeSyncService.DeleteOutcome.NOT_FOUND);

        IndexReconciliationService.Report report =
                service.reconcile(seen("kept"), complete(), 0, inScope, enabled(1.0));

        // The document is gone, which is the desired end state however it got there.
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.failed()).isZero();
    }

    @Test
    void scansUnderTheQualifiedSourceId() {
        indexHolds(doc("a", PREFIX + "/a.txt"));

        service.reconcile(seen("a"), complete(), 0, inScope, enabled(1.0));

        verify(hxprService).forEachDocumentOfSource(org.mockito.ArgumentMatchers.eq("alfresco:repo-uuid"),
                anyInt(), any());
    }

    @Test
    void underAnyPath_matchesTheRootItselfAndItsDescendants_butNotASiblingWithTheSamePrefixText() {
        Predicate<HxprDocument> predicate = IndexReconciliationService.underAnyPath(List.of("/root/a"));

        assertThat(predicate.test(doc("1", "/root/a"))).isTrue();
        assertThat(predicate.test(doc("2", "/root/a/deep/file.txt"))).isTrue();
        // "/root/ab" must not match "/root/a": a plain startsWith would delete a sibling folder.
        assertThat(predicate.test(doc("3", "/root/ab/file.txt"))).isFalse();
        assertThat(predicate.test(doc("4", "/other/file.txt"))).isFalse();
    }

    private static ReconcileConfig enabled(double maxRatio) {
        return new ReconcileConfig(true, maxRatio, 1000, 200_000, 200);
    }

    private static DiscoveryOutcome complete() {
        return DiscoveryOutcome.complete(List.of(PREFIX));
    }

    private static SeenSet seen(String... nodeIds) {
        SeenSet set = new SeenSet(1000);
        for (String nodeId : nodeIds) {
            set.add(nodeId);
        }
        return set;
    }

    private void verifyNothingDeleted() {
        verify(nodeSyncService, never()).delete(any());
    }

    @SuppressWarnings("unchecked")
    private void indexHolds(HxprDocument... documents) {
        doAnswer(invocation -> {
            Consumer<HxprDocument> consumer = invocation.getArgument(2);
            for (HxprDocument document : documents) {
                consumer.accept(document);
            }
            return documents.length;
        }).when(hxprService).forEachDocumentOfSource(anyString(), anyInt(), any(Consumer.class));
    }

    private static HxprDocument doc(String nodeId, String... paths) {
        HxprDocument document = new HxprDocument();
        document.setCinId(nodeId);
        document.setSysId("hxpr-" + nodeId);
        document.setSysName(nodeId + ".txt");
        document.setCinPaths(paths == null ? null : List.of(paths));
        return document;
    }
}
