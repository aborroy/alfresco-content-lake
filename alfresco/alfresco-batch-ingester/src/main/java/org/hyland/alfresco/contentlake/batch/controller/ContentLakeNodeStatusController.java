package org.hyland.alfresco.contentlake.batch.controller;

import lombok.RequiredArgsConstructor;
import org.hyland.alfresco.contentlake.batch.model.NodeStatusBulkRequest;
import org.hyland.contentlake.model.ContentLakeNodeStatus;
import org.hyland.contentlake.model.IndexProof;
import org.hyland.contentlake.service.IndexProofService;
import org.hyland.alfresco.contentlake.service.ContentLakeNodeStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/content-lake/nodes")
@RequiredArgsConstructor
public class ContentLakeNodeStatusController {

    private final ContentLakeNodeStatusService contentLakeNodeStatusService;

    @GetMapping("/{nodeId}/status")
    public ContentLakeNodeStatus getNodeStatus(
            @PathVariable String nodeId,
            @RequestParam(name = "includeFolderAggregate", defaultValue = "false") boolean includeFolderAggregate
    ) {
        return contentLakeNodeStatusService.getNodeStatus(nodeId, includeFolderAggregate);
    }

    /**
     * Measured evidence that a node is retrievable, as opposed to the status
     * {@link #getNodeStatus} reports.
     *
     * <p>{@code status=INDEXED} is a claim: it is read off the Alfresco node and a document present
     * with zero embeddings reports it while being invisible to search. This route counts the chunks
     * on the embeddings index and returns the claims beside them for comparison.</p>
     *
     * <p>Authenticated like every other {@code /api/**} route by the chain's
     * {@code anyRequest().authenticated()}, and the node is resolved on the caller's Alfresco
     * credentials, so no chunk text is returned for a node the caller cannot read.</p>
     *
     * @param sampleSize chunks to include in the sample; clamped, so the response stays bounded
     *                   however large the document is
     */
    @GetMapping("/{nodeId}/index-proof")
    public IndexProof getIndexProof(
            @PathVariable String nodeId,
            @RequestParam(name = "sampleSize", required = false) Integer sampleSize
    ) {
        return contentLakeNodeStatusService.getIndexProof(nodeId,
                sampleSize != null ? sampleSize : IndexProofService.DEFAULT_SAMPLE_SIZE);
    }

    @PostMapping("/status")
    public Map<String, ContentLakeNodeStatus> getNodeStatuses(@RequestBody(required = false) NodeStatusBulkRequest request) {
        List<String> nodeIds = request != null && request.nodeIds() != null
                ? request.nodeIds()
                : List.of();
        boolean includeFolderAggregate = request != null && Boolean.TRUE.equals(request.includeFolderAggregate());
        return contentLakeNodeStatusService.getNodeStatuses(nodeIds, includeFolderAggregate);
    }
}
