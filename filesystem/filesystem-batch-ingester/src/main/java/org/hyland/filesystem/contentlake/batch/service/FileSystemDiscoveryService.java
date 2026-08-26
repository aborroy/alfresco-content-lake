package org.hyland.filesystem.contentlake.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.spi.SourceNode;
import org.hyland.filesystem.contentlake.client.FileSystemSourceClient;
import org.hyland.filesystem.contentlake.config.FileSystemProperties;
import org.hyland.filesystem.contentlake.service.FileSystemScopeResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursively walks the configured filesystem root, returning in-scope file {@link SourceNode}s.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSystemDiscoveryService {

    private final FileSystemSourceClient client;
    private final FileSystemScopeResolver scopeResolver;
    private final FileSystemProperties properties;

    public List<SourceNode> discover() {
        List<SourceNode> discovered = new ArrayList<>();
        String rootNodeId = client.getRootNodeId();
        SourceNode root = client.getNode(rootNodeId);
        collect(root, discovered);
        log.info("Filesystem discovery from {} found {} in-scope files", rootNodeId, discovered.size());
        return discovered;
    }

    private void collect(SourceNode node, List<SourceNode> discovered) {
        if (!node.folder()) {
            if (scopeResolver.isInScope(node)) {
                discovered.add(node);
            }
            return;
        }
        if (!scopeResolver.shouldTraverse(node)) {
            return;
        }
        int skip = 0;
        int pageSize = properties.getPageSize();
        while (true) {
            List<SourceNode> children = client.getChildren(node.nodeId(), skip, pageSize);
            if (children.isEmpty()) {
                break;
            }
            for (SourceNode child : children) {
                collect(child, discovered);
            }
            if (children.size() < pageSize) {
                break;
            }
            skip += children.size();
        }
    }
}
