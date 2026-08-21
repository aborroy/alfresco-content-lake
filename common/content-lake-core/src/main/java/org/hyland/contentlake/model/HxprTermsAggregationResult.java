package org.hyland.contentlake.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Response wrapper for {@code POST /api/query/termsAggregation}.
 * <p>
 * The endpoint returns a {@code QueryResult}-shaped object augmented with an
 * {@code aggregationsBuckets} array; the remaining query fields (documents,
 * count, offset, ...) are empty for an aggregation and are ignored here.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HxprTermsAggregationResult {

    private List<Bucket> aggregationsBuckets;

    /** A single terms-aggregation bucket: a property value and its document count. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bucket {
        private String key;
        private long docCount;
    }
}
