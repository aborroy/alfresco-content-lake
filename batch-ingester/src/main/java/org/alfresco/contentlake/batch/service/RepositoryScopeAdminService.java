package org.alfresco.contentlake.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.alfresco.contentlake.batch.config.IngestionProperties;
import org.alfresco.contentlake.batch.model.ScopeUpdateRequest;
import org.alfresco.contentlake.batch.model.ScopeUpdateResponse;
import org.alfresco.contentlake.client.AlfrescoClient;
import org.alfresco.contentlake.service.ContentLakeScopeResolver;
import org.alfresco.core.model.Node;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Slf4j
@Service
public class RepositoryScopeAdminService {

    private final AlfrescoClient alfrescoClient;
    private final ContentLakeScopeResolver scopeResolver;
    private final Set<String> adminUsers;

    public RepositoryScopeAdminService(AlfrescoClient alfrescoClient,
                                       IngestionProperties props,
                                       ContentLakeScopeResolver scopeResolver) {
        this.alfrescoClient = alfrescoClient;
        this.scopeResolver = scopeResolver;
        this.adminUsers = Set.copyOf(props.getScope().getAdminUsers());
    }

    public ScopeUpdateResponse setIndexedFolderScope(String username, ScopeUpdateRequest request) {
        ensureAuthorized(username);
        validateRequest(request);

        Node node = alfrescoClient.getNode(request.nodeId());
        if (node == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Node not found: " + request.nodeId());
        }
        if (!Boolean.TRUE.equals(node.isIsFolder())) {
            throw new ResponseStatusException(BAD_REQUEST, "Node is not a folder: " + request.nodeId());
        }

        List<String> currentAspects = node.getAspectNames() != null
                ? new ArrayList<>(node.getAspectNames())
                : new ArrayList<>();
        boolean currentlyIndexed = currentAspects.contains(ContentLakeScopeResolver.INDEXED_ASPECT);

        if (currentlyIndexed == request.indexed()) {
            return new ScopeUpdateResponse(node.getId(), request.indexed(), false, username);
        }

        if (request.indexed()) {
            currentAspects.add(ContentLakeScopeResolver.INDEXED_ASPECT);
        } else {
            currentAspects.removeIf(ContentLakeScopeResolver.INDEXED_ASPECT::equals);
        }

        // Only aspects are changing; pass null to leave existing properties untouched.
        alfrescoClient.updateNode(node.getId(), currentAspects, null);

        // Remove the folder from the ancestor-scope cache so subsequent scope checks
        // reflect the new aspect state without waiting for cache eviction.
        scopeResolver.invalidateFolderScope(node.getId());

        log.info("Updated Content Lake scope for folder {}: indexed={} by {}", node.getId(), request.indexed(), username);

        return new ScopeUpdateResponse(node.getId(), request.indexed(), true, username);
    }

    private void ensureAuthorized(String username) {
        if (username == null || !adminUsers.contains(username)) {
            throw new ResponseStatusException(FORBIDDEN, "User is not allowed to modify Content Lake scope");
        }
    }

    private void validateRequest(ScopeUpdateRequest request) {
        if (request == null || request.nodeId() == null || request.nodeId().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "nodeId is required");
        }
    }
}
