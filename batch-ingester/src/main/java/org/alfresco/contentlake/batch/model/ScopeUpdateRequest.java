package org.alfresco.contentlake.batch.model;

/**
 * Explicit admin request used to add or remove the Content Lake scope marker
 * on an Alfresco folder.
 */
public record ScopeUpdateRequest(String nodeId, boolean indexed) {
}
