package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request body for {@code POST /api/graph/graphdbs/{id}/query}. For a v2 graphDB {@code query} is a
 * Dgraph GraphQL query; {@code vars} are its GraphQL variables (string-valued).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphQueryRequest {
    private String query;
    private Map<String, String> vars;
}
