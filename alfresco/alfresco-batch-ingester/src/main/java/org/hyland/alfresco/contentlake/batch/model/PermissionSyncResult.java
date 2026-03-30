package org.hyland.alfresco.contentlake.batch.model;

public record PermissionSyncResult(
        int updated,
        int deleted,
        int skipped,
        int failed
) {
}
