package org.hyland.contentlake.model.graph;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request element for {@code POST /api/graph/graphdbs/{id}/entities}.
 *
 * <p>hxpr flattens extra entity properties at the JSON root (its {@code GraphEntityRequest} uses
 * {@code @JsonAnySetter}), so this send-side model mirrors that with {@link JsonAnyGetter}: {@code uid}
 * / {@code clientRef} / {@code type} serialize as named fields and every entry in {@link #properties}
 * is emitted as a sibling root property (e.g. {@code documentId}, {@code canonical_name}).</p>
 *
 * <p>For a v2 graphDB {@code type} must be one of the fixed schema types (Document, GlobalEntity,
 * TextChunk, LocalEntity, MetadataKV, BusinessObject) and property names must match that type's schema
 * fields. {@code clientRef} is the caller's correlation key echoed back paired with the assigned uid.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphEntityUpsert {

    private final String uid;
    private final String clientRef;
    private final String type;
    private final Map<String, Object> properties;

    public GraphEntityUpsert(String uid, String clientRef, String type, Map<String, Object> properties) {
        this.uid = uid;
        this.clientRef = clientRef;
        this.type = type;
        this.properties = properties != null ? properties : new LinkedHashMap<>();
    }

    public static GraphEntityUpsert of(String clientRef, String type, Map<String, Object> properties) {
        return new GraphEntityUpsert(null, clientRef, type, properties);
    }

    public String getUid() {
        return uid;
    }

    public String getClientRef() {
        return clientRef;
    }

    public String getType() {
        return type;
    }

    /** Flattened to the JSON root to match hxpr's entity-property contract. */
    @JsonAnyGetter
    public Map<String, Object> getProperties() {
        return properties;
    }
}
