package org.alfresco.contentlake.batch.model;

import lombok.Data;

import java.time.Instant;

@Data
public class TransformationTask {

    private String nodeId;
    private String hxprDocumentId;
    private String mimeType;
    private Instant createdAt;
    private int retryCount;

    /** Original document name (e.g. "Annual_Report_2025.pdf"). Used for metadata-enriched embedding. */
    private String documentName;

    /** Repository path (e.g. "/Company Home/Reports"). Used for metadata-enriched embedding. */
    private String documentPath;

    public TransformationTask(String nodeId, String hxprDocumentId, String mimeType) {
        this.nodeId = nodeId;
        this.hxprDocumentId = hxprDocumentId;
        this.mimeType = mimeType;
        this.createdAt = Instant.now();
        this.retryCount = 0;
    }

    public TransformationTask(String nodeId, String hxprDocumentId, String mimeType,
                              String documentName, String documentPath) {
        this(nodeId, hxprDocumentId, mimeType);
        this.documentName = documentName;
        this.documentPath = documentPath;
    }
}