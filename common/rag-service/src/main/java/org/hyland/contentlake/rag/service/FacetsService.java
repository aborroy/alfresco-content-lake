package org.hyland.contentlake.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprTermsAggregationResult;
import org.hyland.contentlake.rag.model.FacetsRequest;
import org.hyland.contentlake.rag.model.FacetsResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Faceted search: returns the most common values of a single indexed property so callers can
 * discover filter values instead of guessing them.
 *
 * <p>Backed by hxpr's {@code termsAggregation}. Counts are ACL-scoped: the aggregation query is
 * the permission filter built for the current user by {@link HybridSearchService}, so a caller
 * only ever sees values (and counts) from documents they may read.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FacetsService {

    static final int DEFAULT_TOP_N = 10;
    static final int MAX_TOP_N = 100;

    private final HxprService hxprService;
    private final HybridSearchService hybridSearchService;

    public FacetsResponse facets(FacetsRequest request) {
        String property = request.getProperty();
        int topN = request.getTopN() > 0 ? Math.min(request.getTopN(), MAX_TOP_N) : DEFAULT_TOP_N;

        String permissionFilter = hybridSearchService.buildCurrentUserPermissionFilter(
                request.getSourceType(), request.getFilter());

        log.debug("Facets request: property={}, topN={}, sourceType={}", property, topN, request.getSourceType());

        HxprTermsAggregationResult result =
                hxprService.termsAggregation(permissionFilter, property, request.getSearchTerm(), topN);

        List<FacetsResponse.Bucket> buckets = (result == null || result.getAggregationsBuckets() == null)
                ? List.of()
                : result.getAggregationsBuckets().stream()
                        .map(b -> FacetsResponse.Bucket.builder()
                                .value(b.getKey())
                                .count(b.getDocCount())
                                .build())
                        .toList();

        return FacetsResponse.builder()
                .property(property)
                .buckets(buckets)
                .build();
    }
}
