package org.hyland.alfresco.contentlake.batch.model;

import lombok.Data;

import java.util.List;

@Data
public class PermissionSyncRequest {

    private List<String> nodeIds;
    private boolean recursive = true;
}
