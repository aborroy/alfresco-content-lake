package org.hyland.contentlake.rag.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for the faceted-search endpoint.
 *
 * <p>Returns the most common values of a single indexed property, so callers can discover
 * what MIME types, folder paths or metadata values exist rather than guessing HXQL filter
 * values. Counts are scoped to the authenticated user's document permissions.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FacetsRequest {

    /** The property to aggregate on (e.g. {@code cin_ingestProperties.mimeType}). Required. */
    private String property;

    /** Optional additional HXQL filter, AND-ed onto the permission scope. */
    private String filter;

    /** Optional source-type restriction ("alfresco" / "nuxeo"). */
    private String sourceType;

    /** Optional term to restrict which property values are aggregated. */
    private String searchTerm;

    /** Maximum number of buckets to return (default from config when {@code <= 0}). */
    @Builder.Default
    private int topN = 0;
}
