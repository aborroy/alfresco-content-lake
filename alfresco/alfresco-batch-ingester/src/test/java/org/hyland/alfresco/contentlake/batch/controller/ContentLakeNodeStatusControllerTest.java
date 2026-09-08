package org.hyland.alfresco.contentlake.batch.controller;

import org.hyland.alfresco.contentlake.batch.model.NodeStatusBulkRequest;
import org.hyland.contentlake.model.ContentLakeNodeStatus;
import org.hyland.contentlake.model.IndexProof;
import org.hyland.contentlake.service.IndexProofService;
import org.hyland.alfresco.contentlake.service.ContentLakeNodeStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContentLakeNodeStatusControllerTest {

    private CapturingNodeStatusService statusService;
    private ContentLakeNodeStatusController controller;

    @BeforeEach
    void setUp() {
        statusService = new CapturingNodeStatusService();
        controller = new ContentLakeNodeStatusController(statusService);
    }

    @Test
    void getNodeStatus_passesAggregateFlagToService() {
        ContentLakeNodeStatus expected = new ContentLakeNodeStatus("folder-1", null, true, true, true, false, null);
        statusService.nodeStatusResponse = expected;

        ContentLakeNodeStatus actual = controller.getNodeStatus("folder-1", true);

        assertThat(actual).isEqualTo(expected);
        assertThat(statusService.lastNodeId).isEqualTo("folder-1");
        assertThat(statusService.lastIncludeFolderAggregate).isTrue();
    }

    @Test
    void getNodeStatuses_defaultsAggregateFlagToFalseWhenRequestMissing() {
        Map<String, ContentLakeNodeStatus> expected = Map.of();
        statusService.bulkStatusResponse = expected;

        Map<String, ContentLakeNodeStatus> actual = controller.getNodeStatuses(null);

        assertThat(actual).isSameAs(expected);
        assertThat(statusService.lastNodeIds).isEmpty();
        assertThat(statusService.lastIncludeFolderAggregate).isFalse();
    }

    @Test
    void getNodeStatuses_passesAggregateFlagFromRequest() {
        NodeStatusBulkRequest request = new NodeStatusBulkRequest(List.of("folder-1", "file-1"), true);
        Map<String, ContentLakeNodeStatus> expected = Map.of(
                "folder-1", new ContentLakeNodeStatus("folder-1", ContentLakeNodeStatus.Status.PENDING, true, true, true, false, null),
                "file-1", new ContentLakeNodeStatus("file-1", ContentLakeNodeStatus.Status.INDEXED, true, false, true, false, null)
        );
        statusService.bulkStatusResponse = expected;

        Map<String, ContentLakeNodeStatus> actual = controller.getNodeStatuses(request);

        assertThat(actual).isSameAs(expected);
        assertThat(statusService.lastNodeIds).containsExactly("folder-1", "file-1");
        assertThat(statusService.lastIncludeFolderAggregate).isTrue();
    }

    @Test
    void getIndexProof_defaultsTheSampleSizeWhenTheCallerOmitsIt() {
        statusService.indexProofResponse = proof("file-1");

        IndexProof actual = controller.getIndexProof("file-1", null);

        assertThat(actual).isSameAs(statusService.indexProofResponse);
        assertThat(statusService.lastNodeId).isEqualTo("file-1");
        assertThat(statusService.lastSampleSize).isEqualTo(IndexProofService.DEFAULT_SAMPLE_SIZE);
    }

    @Test
    void getIndexProof_passesTheRequestedSampleSizeThrough() {
        statusService.indexProofResponse = proof("file-1");

        controller.getIndexProof("file-1", 3);

        assertThat(statusService.lastSampleSize).isEqualTo(3);
    }

    private static IndexProof proof(String nodeId) {
        return new IndexProof(nodeId, IndexProof.Verdict.METADATA_ONLY,
                new IndexProof.Measured(true, "doc-1", "alfresco:repo", 0L, false,
                        List.of(), List.of(), List.of()),
                new IndexProof.Claimed("INDEXED", null, null, true, "INDEXED", 3),
                null);
    }

    private static final class CapturingNodeStatusService extends ContentLakeNodeStatusService {
        private String lastNodeId;
        private List<String> lastNodeIds = List.of();
        private boolean lastIncludeFolderAggregate;
        private int lastSampleSize;
        private ContentLakeNodeStatus nodeStatusResponse;
        private IndexProof indexProofResponse;
        private Map<String, ContentLakeNodeStatus> bulkStatusResponse = new LinkedHashMap<>();

        private CapturingNodeStatusService() {
            super(null, null, null, null, Runnable::run, null);
        }

        @Override
        public IndexProof getIndexProof(String nodeId, int sampleSize) {
            lastNodeId = nodeId;
            lastSampleSize = sampleSize;
            return indexProofResponse;
        }

        @Override
        public ContentLakeNodeStatus getNodeStatus(String nodeId, boolean includeFolderAggregate) {
            lastNodeId = nodeId;
            lastIncludeFolderAggregate = includeFolderAggregate;
            return nodeStatusResponse;
        }

        @Override
        public Map<String, ContentLakeNodeStatus> getNodeStatuses(Collection<String> nodeIds, boolean includeFolderAggregate) {
            lastNodeIds = List.copyOf(nodeIds);
            lastIncludeFolderAggregate = includeFolderAggregate;
            return bulkStatusResponse;
        }
    }
}
