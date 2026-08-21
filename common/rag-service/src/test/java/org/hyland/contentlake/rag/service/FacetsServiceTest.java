package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprTermsAggregationResult;
import org.hyland.contentlake.rag.model.FacetsRequest;
import org.hyland.contentlake.rag.model.FacetsResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FacetsServiceTest {

    @Mock
    private HxprService hxprService;

    @Mock
    private HybridSearchService hybridSearchService;

    @InjectMocks
    private FacetsService facetsService;

    private static HxprTermsAggregationResult.Bucket bucket(String key, long count) {
        HxprTermsAggregationResult.Bucket b = new HxprTermsAggregationResult.Bucket();
        b.setKey(key);
        b.setDocCount(count);
        return b;
    }

    private static HxprTermsAggregationResult result(HxprTermsAggregationResult.Bucket... buckets) {
        HxprTermsAggregationResult r = new HxprTermsAggregationResult();
        r.setAggregationsBuckets(List.of(buckets));
        return r;
    }

    @Test
    void facets_scopesTheAggregationToTheCurrentUserPermissionFilter() {
        when(hybridSearchService.buildCurrentUserPermissionFilter("alfresco", "my-filter"))
                .thenReturn("SELECT * FROM SysContent WHERE (sys_racl = 'u:bob_#_repo')");
        when(hxprService.termsAggregation(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(result());

        facetsService.facets(FacetsRequest.builder()
                .property("cin_ingestProperties.mimeType")
                .sourceType("alfresco")
                .filter("my-filter")
                .searchTerm("pdf")
                .topN(5)
                .build());

        verify(hxprService).termsAggregation(
                eq("SELECT * FROM SysContent WHERE (sys_racl = 'u:bob_#_repo')"),
                eq("cin_ingestProperties.mimeType"),
                eq("pdf"),
                eq(5));
    }

    @Test
    void facets_mapsBucketsPreservingOrderAndCounts() {
        when(hybridSearchService.buildCurrentUserPermissionFilter(any(), any()))
                .thenReturn("SELECT * FROM SysContent");
        when(hxprService.termsAggregation(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(result(bucket("application/pdf", 79), bucket("text/plain", 12)));

        FacetsResponse response = facetsService.facets(FacetsRequest.builder()
                .property("cin_ingestProperties.mimeType")
                .build());

        assertThat(response.getProperty()).isEqualTo("cin_ingestProperties.mimeType");
        assertThat(response.getBuckets()).hasSize(2);
        assertThat(response.getBuckets().getFirst().getValue()).isEqualTo("application/pdf");
        assertThat(response.getBuckets().getFirst().getCount()).isEqualTo(79);
        assertThat(response.getBuckets().get(1).getValue()).isEqualTo("text/plain");
    }

    @Test
    void facets_defaultsTopNWhenUnsetAndClampsToMax() {
        when(hybridSearchService.buildCurrentUserPermissionFilter(any(), any()))
                .thenReturn("SELECT * FROM SysContent");
        when(hxprService.termsAggregation(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(result());
        ArgumentCaptor<Integer> topN = ArgumentCaptor.forClass(Integer.class);

        facetsService.facets(FacetsRequest.builder().property("p").build());              // unset -> default
        facetsService.facets(FacetsRequest.builder().property("p").topN(10_000).build()); // over max -> clamp

        verify(hxprService, org.mockito.Mockito.times(2))
                .termsAggregation(any(), any(), any(), topN.capture());
        assertThat(topN.getAllValues().get(0)).isEqualTo(FacetsService.DEFAULT_TOP_N);
        assertThat(topN.getAllValues().get(1)).isEqualTo(FacetsService.MAX_TOP_N);
    }

    @Test
    void facets_nullResult_returnsEmptyBuckets() {
        when(hybridSearchService.buildCurrentUserPermissionFilter(any(), any()))
                .thenReturn("SELECT * FROM SysContent");
        when(hxprService.termsAggregation(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(null);

        FacetsResponse response = facetsService.facets(FacetsRequest.builder().property("p").build());

        assertThat(response.getBuckets()).isEmpty();
    }
}
