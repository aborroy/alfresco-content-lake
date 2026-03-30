package org.hyland.alfresco.contentlake.repo.acl;

import org.alfresco.service.cmr.repository.NodeRef;

import java.util.Objects;

public record AclChangeRequest(
        String nodeId,
        boolean recursive,
        String reason
) {

    public AclChangeRequest {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        reason = reason == null || reason.isBlank() ? "acl-change" : reason;
    }

    public static AclChangeRequest of(NodeRef nodeRef, boolean recursive, String reason) {
        Objects.requireNonNull(nodeRef, "nodeRef must not be null");
        return new AclChangeRequest(nodeRef.getId(), recursive, reason);
    }

    public AclChangeRequest merge(AclChangeRequest other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!nodeId.equals(other.nodeId())) {
            throw new IllegalArgumentException("Cannot merge ACL changes for different nodes");
        }
        return recursive || other.recursive()
                ? new AclChangeRequest(nodeId, true, reason)
                : this;
    }
}
