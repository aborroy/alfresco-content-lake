package org.hyland.alfresco.contentlake.batch.model;

public record PermissionSyncMessage(
        String eventType,
        String sourceType,
        String nodeId,
        boolean recursive,
        String reason,
        String occurredAt
) {
}
