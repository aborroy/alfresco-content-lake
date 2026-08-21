package org.hyland.contentlake.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Response wrapper for {@code GET /api/query/named}, which returns the names of
 * the registered named-query definitions as {@code {"namedQueries": [...]}}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HxprNamedQueries {

    private List<String> namedQueries;
}
