package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Canonical response from the hxpr graphDB endpoints
 * ({@code POST}/{@code GET /api/graph/graphdbs}). Carries {@code graphDBId},
 * {@code graphDBName} and the schema {@code version} label.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphDbResponse {
    private String graphDBId;
    private String graphDBName;
    private String version;
}
