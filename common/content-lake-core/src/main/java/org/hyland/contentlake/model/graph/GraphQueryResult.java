package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Response from {@code POST /api/graph/graphdbs/{id}/query}. hxpr returns a single row whose
 * {@code result} value is a JSON string mirroring the GraphQL selection set - parse it client-side.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphQueryResult {
    private List<Map<String, Object>> rows = new ArrayList<>();

    /** The raw {@code rows[0].result} JSON string, or null when there are no rows. */
    public String firstResultJson() {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Object result = rows.get(0).get("result");
        return result != null ? result.toString() : null;
    }
}
