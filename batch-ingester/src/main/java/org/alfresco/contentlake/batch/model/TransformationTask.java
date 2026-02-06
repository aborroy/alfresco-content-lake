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

    public TransformationTask(String nodeId, String hxprDocumentId, String mimeType) {
        this.nodeId = nodeId;
        this.hxprDocumentId = hxprDocumentId;
        this.mimeType = mimeType;
        this.createdAt = Instant.now();
        this.retryCount = 0;
    }
}
