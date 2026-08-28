package org.hyland.alfresco.contentlake.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.core.model.Node;
import org.hyland.alfresco.contentlake.adapter.AlfrescoSourceNodeAdapter;
import org.hyland.alfresco.contentlake.batch.model.PermissionSyncRequest;
import org.hyland.alfresco.contentlake.batch.model.PermissionSyncResult;
import org.hyland.alfresco.contentlake.client.AlfrescoClient;
import org.hyland.alfresco.contentlake.client.AlfrescoSearchService;
import org.hyland.alfresco.contentlake.service.ContentLakeScopeResolver;
import org.hyland.contentlake.service.NodeSyncService;
import org.hyland.contentlake.spi.SourceNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Explicit ACL reconciliation path used when repository permission changes are
 * not emitted as live events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionSyncService {

    private final AlfrescoClient alfrescoClient;
    private final AlfrescoSearchService searchService;
    private final ContentLakeScopeResolver scopeResolver;
    private final NodeSyncService nodeSyncService;

    // Same discovery-retry knobs as batch ingestion (issue #88): the AFTS ANCESTOR descendant
    // query can momentarily return empty while the OpenSearch batch indexer catches up.
    @Value("${ingestion.discovery.max-attempts:${INGESTION_DISCOVERY_MAX_ATTEMPTS:10}}")
    private int discoveryMaxAttempts;

    @Value("${ingestion.discovery.retry-interval-ms:${INGESTION_DISCOVERY_RETRY_INTERVAL_MS:3000}}")
    private long discoveryRetryIntervalMs;

    public PermissionSyncResult syncPermissions(PermissionSyncRequest request) {
        MutableResult result = new MutableResult();
        if (request == null || request.getNodeIds() == null || request.getNodeIds().isEmpty()) {
            return result.toImmutable();
        }

        for (String nodeId : request.getNodeIds()) {
            if (nodeId == null || nodeId.isBlank()) {
                result.skipped++;
                continue;
            }
            reconcileNode(nodeId.trim(), request.isRecursive(), result);
        }

        return result.toImmutable();
    }

    private void reconcileNode(String nodeId, boolean recursive, MutableResult result) {
        try {
            Node node = alfrescoClient.getAlfrescoNode(nodeId);
            if (node == null) {
                result.skipped++;
                log.warn("Skipping permission reconciliation for missing node {}", nodeId);
                return;
            }

            if (Boolean.TRUE.equals(node.isIsFolder())) {
                reconcileFolder(node, recursive, result);
                return;
            }

            reconcileFile(node, result);
        } catch (Exception e) {
            result.failed++;
            log.error("Failed permission reconciliation for node {}", nodeId, e);
        }
    }

    private void reconcileFolder(Node folder, boolean recursive, MutableResult result) {
        if (!recursive) {
            result.skipped++;
            log.info("Skipping non-recursive permission reconciliation for folder {}", folder.getId());
            return;
        }
        if (!scopeResolver.shouldTraverse(folder)) {
            result.skipped++;
            log.info("Skipping permission reconciliation for excluded folder {}", folder.getId());
            return;
        }

        for (Node child : searchService.findDescendantFilesWithRetry(
                folder.getId(), scopeResolver.getExcludedAspects(),
                discoveryMaxAttempts, discoveryRetryIntervalMs)) {
            try {
                reconcileFile(child, result);
            } catch (Exception e) {
                result.failed++;
                log.error("Failed permission reconciliation for descendant {}", child.getId(), e);
            }
        }
    }

    private void reconcileFile(Node file, MutableResult result) {
        // REST-based scope check: an ACL change fires this reconciliation immediately, while the
        // OpenSearch batch indexer may still be re-indexing the affected subtree. The AFTS-based
        // isInScope would race that and wrongly delete an in-scope document (issue #88).
        if (!scopeResolver.isInScopeViaRest(file)) {
            nodeSyncService.deleteNode(file.getId(), file.getModifiedAt());
            result.deleted++;
            return;
        }

        nodeSyncService.updatePermissions(toSourceNode(file));
        result.updated++;
    }

    private SourceNode toSourceNode(Node node) {
        Set<String> readers = alfrescoClient.extractReadAuthorities(node);
        return AlfrescoSourceNodeAdapter.toSourceNode(node, alfrescoClient.getSourceId(), readers);
    }

    private static final class MutableResult {
        private int updated;
        private int deleted;
        private int skipped;
        private int failed;

        private PermissionSyncResult toImmutable() {
            return new PermissionSyncResult(updated, deleted, skipped, failed);
        }
    }
}
