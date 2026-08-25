package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request element for {@code POST /api/graph/graphdbs/{id}/relationships}.
 *
 * <p>{@code sourceUid}/{@code targetUid} are the Dgraph uids returned by the entity upsert.
 * {@code relationshipType} is a schema edge predicate of {@code sourceType} (e.g. {@code
 * has_global_entity}); {@code sourceType} is required for v2 validation. The schema's {@code
 * @hasInverse} auto-emits the reverse edge.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphRelationshipUpsert {
    private String sourceUid;
    private String targetUid;
    private String relationshipType;
    private String sourceType;
    private String targetType;
}
