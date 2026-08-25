package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/graph/graphdbs}.
 *
 * <p>{@code version} is the hxpr schema version label ({@code "v1"} or {@code "v2"});
 * {@code "v2"} selects the ACL-aware Dgraph schema. Field names match the hxpr wire
 * contract exactly.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphDbRequest {
    private String graphDBName;
    private String version;
}
