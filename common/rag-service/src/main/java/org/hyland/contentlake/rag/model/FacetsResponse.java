package org.hyland.contentlake.rag.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response payload for the faceted-search endpoint: the aggregated property and its top buckets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FacetsResponse {

    /** The property the buckets were aggregated on. */
    private String property;

    /** Top buckets, ordered by document count as returned by hxpr. */
    private List<Bucket> buckets;

    /** A single facet bucket: a property value and how many readable documents carry it. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Bucket {
        private String value;
        private long count;
    }
}
