package org.hyland.contentlake.client;

import org.hyland.contentlake.hxpr.api.model.AdvancedQuery;
import org.hyland.contentlake.hxpr.api.model.NamedQuery;
import org.hyland.contentlake.hxpr.api.model.TermsAggregationsQuery;
import org.hyland.contentlake.hxpr.api.model.VectorQuery;
import org.hyland.contentlake.hxpr.api.model.VectorSearchResult;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.model.HxprNamedQueries;
import org.hyland.contentlake.model.HxprTermsAggregationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the Sprint 3 advanced-query client methods: their request bodies must be built from the
 * structured hxpr models rather than concatenated HXQL, and responses mapped through the
 * hand-written wrappers.
 */
@ExtendWith(MockitoExtension.class)
class HxprServiceQueryApiTest {

    @Mock
    private HxprDocumentApi documentApi;
    @Mock
    private HxprQueryApi queryApi;
    @Mock
    private RestClient restClient;

    private HxprService service() {
        return new HxprService(documentApi, queryApi, restClient);
    }

    @Test
    void advancedQuery_sendsEachClauseIndependentlyWithPositiveLimit() {
        when(queryApi.advancedQuery(any())).thenReturn(new HxprDocument.QueryResult());

        service().advancedQuery("SELECT * FROM SysContent",
                List.of("cin_id = 'a'", "cin_ingestProperties.source_type = 'alfresco'"), 25, 0);

        ArgumentCaptor<AdvancedQuery> captor = ArgumentCaptor.forClass(AdvancedQuery.class);
        verify(queryApi).advancedQuery(captor.capture());
        AdvancedQuery issued = captor.getValue();
        assertThat(issued.getQuery()).isEqualTo("SELECT * FROM SysContent");
        assertThat(issued.getQuickFilterClauses())
                .containsExactly("cin_id = 'a'", "cin_ingestProperties.source_type = 'alfresco'");
        assertThat(issued.getLimit()).isEqualTo(25L);
        assertThat(issued.getTrackTotalCount()).isTrue();
    }

    @Test
    void namedQuery_setsQueryNameAndSelectedQuickFilters() {
        when(queryApi.namedQuery(any())).thenReturn(new HxprDocument.QueryResult());

        service().namedQuery("recent-articles", List.of("articles-only"), 10, 5);

        ArgumentCaptor<NamedQuery> captor = ArgumentCaptor.forClass(NamedQuery.class);
        verify(queryApi).namedQuery(captor.capture());
        NamedQuery issued = captor.getValue();
        assertThat(issued.getQueryName()).isEqualTo("recent-articles");
        assertThat(issued.getSelectedQuickFilters()).containsExactly("articles-only");
        assertThat(issued.getLimit()).isEqualTo(10L);
        assertThat(issued.getOffset()).isEqualTo(5L);
    }

    @Test
    void listNamedQueries_returnsNamesOrEmpty() {
        HxprNamedQueries names = new HxprNamedQueries();
        names.setNamedQueries(List.of("q1", "q2"));
        when(queryApi.listNamedQueries()).thenReturn(names);
        assertThat(service().listNamedQueries()).containsExactly("q1", "q2");

        when(queryApi.listNamedQueries()).thenReturn(new HxprNamedQueries());
        assertThat(service().listNamedQueries()).isEmpty();
    }

    @Test
    void findByNodeId_migratedToIndependentQuickFilterClauses() {
        when(queryApi.advancedQuery(any())).thenReturn(new HxprDocument.QueryResult());

        service().findByNodeId("node-1", "alfresco:repo-1");

        ArgumentCaptor<AdvancedQuery> captor = ArgumentCaptor.forClass(AdvancedQuery.class);
        verify(queryApi).advancedQuery(captor.capture());
        AdvancedQuery issued = captor.getValue();
        assertThat(issued.getQuery()).isEqualTo("SELECT * FROM SysContent");
        assertThat(issued.getQuickFilterClauses())
                .anyMatch(c -> c.contains("cin_id = 'node-1'"))
                .anyMatch(c -> c.contains("sys_primaryType ="))
                .anyMatch(c -> c.contains("cin_sourceId"));
    }

    @Test
    void findByNodeIds_orsIdsIntoASingleClause() {
        when(queryApi.advancedQuery(any())).thenReturn(new HxprDocument.QueryResult());

        service().findByNodeIds(List.of("a", "b"), "alfresco:repo-1");

        ArgumentCaptor<AdvancedQuery> captor = ArgumentCaptor.forClass(AdvancedQuery.class);
        verify(queryApi).advancedQuery(captor.capture());
        assertThat(captor.getValue().getQuickFilterClauses())
                .anyMatch(c -> c.contains("cin_id = 'a'") && c.contains("cin_id = 'b'") && c.contains(" OR "));
    }

    @Test
    void vectorSearch_setsChunkFtsWhenProvidedAndDefaultsEmbeddingTypeToWildcard() {
        when(queryApi.vectorSearch(any())).thenReturn(new VectorSearchResult());

        service().vectorSearch(List.of(0.1d, 0.2d), null, null, "alfresco content", 20);

        ArgumentCaptor<VectorQuery> captor = ArgumentCaptor.forClass(VectorQuery.class);
        verify(queryApi).vectorSearch(captor.capture());
        VectorQuery issued = captor.getValue();
        assertThat(issued.getChunkFTS()).isEqualTo("alfresco content");
        assertThat(issued.getEmbeddingType()).isEqualTo("*");
    }

    @Test
    void vectorSearch_blankChunkFts_leavesItUnset() {
        when(queryApi.vectorSearch(any())).thenReturn(new VectorSearchResult());

        service().vectorSearch(List.of(0.1d, 0.2d), "type-1", null, "  ", 20);

        ArgumentCaptor<VectorQuery> captor = ArgumentCaptor.forClass(VectorQuery.class);
        verify(queryApi).vectorSearch(captor.capture());
        assertThat(captor.getValue().getChunkFTS()).isNull();
    }

    @Test
    void termsAggregation_setsRequiredPropertyAndScopingQuery() {
        when(queryApi.termsAggregation(any())).thenReturn(new HxprTermsAggregationResult());

        service().termsAggregation("SELECT * FROM SysContent WHERE (sys_racl = 'u:bob')",
                "cin_ingestProperties.mimeType", "pdf", 20);

        ArgumentCaptor<TermsAggregationsQuery> captor = ArgumentCaptor.forClass(TermsAggregationsQuery.class);
        verify(queryApi).termsAggregation(captor.capture());
        TermsAggregationsQuery issued = captor.getValue();
        assertThat(issued.getTermsAggregationProperty()).isEqualTo("cin_ingestProperties.mimeType");
        assertThat(issued.getSearchTerm()).isEqualTo("pdf");
        assertThat(issued.getLimit()).isEqualTo(20);
        assertThat(issued.getQuery().getQuery()).isEqualTo("SELECT * FROM SysContent WHERE (sys_racl = 'u:bob')");
    }
}
