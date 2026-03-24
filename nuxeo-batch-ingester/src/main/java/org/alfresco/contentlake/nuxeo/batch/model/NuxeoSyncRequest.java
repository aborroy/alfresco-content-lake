package org.alfresco.contentlake.nuxeo.batch.model;

import lombok.Data;
import org.alfresco.contentlake.config.NuxeoProperties;

import java.util.List;

@Data
public class NuxeoSyncRequest {

    private List<String> includedRoots;
    private List<String> includedDocumentTypes;
    private List<String> excludedLifecycleStates;
    private Integer pageSize;
    private NuxeoProperties.Mode discoveryMode;
}
