package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Response from the hxpr ontology endpoints
 * ({@code POST}/{@code GET /api/graph/ontologies}).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntologyResponse {
    private String ontologyId;
    private String ontologyName;
    private String description;
    private String createdAt;
}
