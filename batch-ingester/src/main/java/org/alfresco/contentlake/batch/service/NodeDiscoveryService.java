package org.alfresco.contentlake.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.contentlake.batch.config.IngestionProperties;
import org.alfresco.contentlake.batch.model.BatchSyncRequest;
import org.alfresco.contentlake.client.AlfrescoClient;
import org.alfresco.contentlake.service.ContentLakeScopeResolver;
import org.alfresco.core.model.Node;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers candidate Alfresco nodes for ingestion based on request parameters or configuration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NodeDiscoveryService {

    private final AlfrescoClient alfrescoClient;
    private final IngestionProperties props;
    private final ContentLakeScopeResolver scopeResolver;

    /**
     * Discovers nodes from the folders specified in the request.
     *
     * @param request discovery configuration
     * @return stream of nodes matching type, MIME type, and exclusion rules
     */
    public Stream<Node> discoverNodes(BatchSyncRequest request) {
        List<String> folders = request.getFolders();
        boolean recursive = request.isRecursive();
        List<String> types = request.getTypes();
        List<String> mimeTypes = request.getMimeTypes();

        return folders.stream()
                .flatMap(folderId -> discoverFromFolder(folderId, recursive, types, mimeTypes));
    }

    /**
     * Discovers nodes using configured sources.
     *
     * @return stream of nodes matching source filters and exclusion rules
     */
    public Stream<Node> discoverFromConfig() {
        return props.getSources().stream()
                .flatMap(source -> discoverFromFolder(
                        source.getFolder(),
                        source.isRecursive(),
                        source.getTypes(),
                        source.getMimeTypes()
                ));
    }

    private Stream<Node> discoverFromFolder(
            String folderId,
            boolean recursive,
            List<String> types,
            List<String> mimeTypes
    ) {
        log.info("Discovering nodes from folder: {}, recursive: {}", folderId, recursive);

        return alfrescoClient.getAllChildren(folderId).stream()
                .flatMap(node -> toCandidateStream(node, recursive, types, mimeTypes));
    }

    private Stream<Node> toCandidateStream(Node node, boolean recursive, List<String> types, List<String> mimeTypes) {
        if (Boolean.TRUE.equals(node.isIsFolder()) && recursive) {
            if (!scopeResolver.shouldTraverse(node)) {
                return Stream.empty();
            }
            return discoverFromFolder(node.getId(), true, types, mimeTypes);
        }

        if (Boolean.FALSE.equals(node.isIsFolder())
                && matchesType(node, types)
                && matchesMimeType(node, mimeTypes)
                && scopeResolver.isInScope(node)) {
            return Stream.of(node);
        }

        return Stream.empty();
    }

    private boolean matchesType(Node node, List<String> types) {
        return types == null || types.isEmpty() || types.contains(node.getNodeType());
    }

    private boolean matchesMimeType(Node node, List<String> mimeTypes) {
        if (mimeTypes == null || mimeTypes.isEmpty()) {
            return true;
        }
        if (node.getContent() == null) {
            return false;
        }
        return mimeTypes.contains(node.getContent().getMimeType());
    }
}
