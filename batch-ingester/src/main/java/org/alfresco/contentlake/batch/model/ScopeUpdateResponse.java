package org.alfresco.contentlake.batch.model;

public record ScopeUpdateResponse(
        String nodeId,
        boolean indexed,
        boolean changed,
        String updatedBy
) {
}
