package org.hyland.contentlake.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.hxpr.api.model.NamedQueryDefinition;
import org.hyland.contentlake.hxpr.api.model.WhereClause;
import org.hyland.contentlake.model.HxprDocument;

import java.util.List;

/**
 * Thin service over hxpr's named-query API: list the registered definitions and execute one by
 * name. Named queries let retrieval patterns be tuned server-side (registered in hxpr) instead of
 * being string-concatenated into HXQL and redeployed with content-lake-app.
 */
@Slf4j
@RequiredArgsConstructor
public class NamedQueryService {

    private final HxprService hxprService;

    /** Returns the names of the named-query definitions registered in hxpr. */
    public List<String> list() {
        return hxprService.listNamedQueries();
    }

    /**
     * Executes a registered named query.
     *
     * @param queryName            name of the named-query definition
     * @param selectedQuickFilters names of the definition's quick filters to apply, may be empty
     * @param limit                max results
     * @param offset               result offset
     * @return the matching documents
     */
    public HxprDocument.QueryResult execute(String queryName, List<String> selectedQuickFilters,
                                            int limit, int offset) {
        log.debug("Executing named query '{}' (quickFilters={}, limit={})",
                queryName, selectedQuickFilters, limit);
        return hxprService.namedQuery(queryName, selectedQuickFilters, limit, offset);
    }

    /**
     * Resolves a named query's definition to an HXQL filter fragment, so the caller can use a named
     * query as an alternative to an inline {@code filter} on vector/hybrid search. Returns the
     * definition's where-clause HXQL; parameterized predicates and opt-in quick filters are not
     * applied (the search request carries only the query name). Returns {@code null} when the query
     * is unknown, has no where-clause HXQL, or cannot be fetched -- retrieval then proceeds unfiltered.
     */
    public String resolveFilter(String queryName) {
        if (queryName == null || queryName.isBlank()) {
            return null;
        }
        NamedQueryDefinition definition;
        try {
            definition = hxprService.getNamedQuery(queryName);
        } catch (Exception e) {
            log.warn("Named query '{}' could not be resolved to a filter (searching unfiltered): {}",
                    queryName, e.getMessage());
            return null;
        }
        if (definition == null || definition.getWhereClauseDefinition() == null) {
            log.warn("Named query '{}' has no where-clause definition; searching unfiltered", queryName);
            return null;
        }
        WhereClause where = definition.getWhereClauseDefinition();
        String hxql = where.getQuery();
        return (hxql != null && !hxql.isBlank()) ? hxql.trim() : null;
    }
}
