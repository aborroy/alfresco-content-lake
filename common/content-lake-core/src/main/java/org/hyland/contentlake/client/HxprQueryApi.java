package org.hyland.contentlake.client;

import org.hyland.contentlake.hxpr.api.model.VectorQuery;
import org.hyland.contentlake.hxpr.api.model.VectorSearchResult;
import org.hyland.contentlake.model.HxprDocument;
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
