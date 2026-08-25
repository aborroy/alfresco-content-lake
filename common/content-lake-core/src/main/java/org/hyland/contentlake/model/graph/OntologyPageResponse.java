package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Paginated response from {@code GET /api/graph/ontologies}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntologyPageResponse {
    private long offset;
    private long limit;
    private long totalCount;
    private List<OntologyResponse> ontologies = new ArrayList<>();
}
