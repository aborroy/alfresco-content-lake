package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single ontology routing rule for
 * {@code PUT /api/graph/graphdbs/{graphDBId}/ontologyroutes}.
 *
 * <p>{@code condition} is an hxpr {@code ExpressionVisitor} boolean expression (uses
 * {@code ==}/{@code !=}/{@code &&}/{@code ||}, dotted property paths, and double-quoted
 * string literals, e.g. {@code content.sys_primaryType == "SysFile"}). {@code ontologyId}
 * must reference an ontology already registered under {@code /api/graph/ontologies}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntologyRoute {
    private String condition;
    private String ontologyId;
}
