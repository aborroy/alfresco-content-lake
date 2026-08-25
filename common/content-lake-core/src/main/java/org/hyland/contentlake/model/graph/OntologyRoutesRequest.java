package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body for {@code PUT /api/graph/graphdbs/{graphDBId}/ontologyroutes}.
 *
 * <p>The PUT replaces the graphDB's entire route list; an empty list clears all routes.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OntologyRoutesRequest {
    private List<OntologyRoute> ontologyRoutes = new ArrayList<>();
}
