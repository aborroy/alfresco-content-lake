package org.hyland.contentlake.client;

import org.hyland.contentlake.hxpr.api.model.AdvancedQuery;
import org.hyland.contentlake.hxpr.api.model.NamedQuery;
import org.hyland.contentlake.hxpr.api.model.NamedQueryDefinition;
import org.hyland.contentlake.hxpr.api.model.TermsAggregationsQuery;
import org.hyland.contentlake.hxpr.api.model.VectorQuery;
import org.hyland.contentlake.hxpr.api.model.VectorSearchResult;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.model.HxprNamedQueries;
import org.hyland.contentlake.model.HxprTermsAggregationResult;
import org.hyland.contentlake.hxpr.api.model.Query;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Spring HTTP Interface for the HXPR Query REST API.
 * <p>
 * Uses generated {@link Query} and {@link VectorQuery} models for request
 * bodies, ensuring type-safe query construction aligned with the OpenAPI spec.
 */
@HttpExchange("/api/query")
public interface HxprQueryApi {

    @PostExchange
    HxprDocument.QueryResult query(@RequestBody Query query);

    @PostExchange("/embeddings")
    VectorSearchResult vectorSearch(@RequestBody VectorQuery query);

    /**
     * Advanced document query. Each entry in {@link AdvancedQuery#getQuickFilterClauses()}
     * is AND-ed independently by hxpr, replacing inline HXQL string concatenation.
     */
    @PostExchange("/advanced")
    HxprDocument.QueryResult advancedQuery(@RequestBody AdvancedQuery query);

    /**
     * Executes a pre-registered named query. The {@link NamedQuery#getQueryName()} must
     * match a definition registered in hxpr; {@code selectedQuickFilters} pick which of the
     * definition's quick filters to apply.
     */
    @PostExchange("/named")
    HxprDocument.QueryResult namedQuery(@RequestBody NamedQuery query);

    /** Returns the names of the registered named-query definitions. */
    @GetExchange("/named")
    HxprNamedQueries listNamedQueries();

    /** Returns the full definition (where-clause, quick filters) of a named query. */
    @GetExchange("/named/{namedQuery}")
    NamedQueryDefinition getNamedQuery(@org.springframework.web.bind.annotation.PathVariable("namedQuery") String namedQuery);

    /**
     * Terms aggregation: returns the top-N distinct values (buckets) of a property together
     * with their document counts, optionally scoped by the embedded {@link Query}.
     */
    @PostExchange("/termsAggregation")
    HxprTermsAggregationResult termsAggregation(@RequestBody TermsAggregationsQuery query);

    /**
     * Blocks until the full-text/query index has caught up with pending writes.
     *
     * <p>hxpr's query index is eventually consistent: a document (e.g. an embedding child)
     * created moments earlier may not yet be visible to {@link #query(Query)}. Calling this
     * before a read-then-delete avoids the race where a stale child is missed and a duplicate
     * is created on re-sync.</p>
     *
     * @param refresh forces an index refresh
     * @param timeout maximum wait in seconds
     */
    @GetExchange("/waitForFullTextSearchIndexing")
    void waitForFullTextSearchIndexing(@RequestParam("refresh") boolean refresh,
                                       @RequestParam("timeout") int timeout);
}
