package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Response element from {@code POST /api/graph/graphdbs/{id}/entities}: the caller's {@code clientRef}
 * paired with the Dgraph-assigned {@code uid}. Correlate by {@code clientRef} (order is not guaranteed).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphEntityUpsertResponse {
    private String clientRef;
    private String uid;
}
