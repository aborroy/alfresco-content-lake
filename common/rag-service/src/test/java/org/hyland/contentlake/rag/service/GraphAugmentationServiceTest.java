package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.client.HxprGraphService;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SourceDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #55: graph expansion parses the traversal result, excludes seeds, and re-filters related documents
 * through the sys_racl permission path before returning them as extra hits.
 */
@ExtendWith(MockitoExtension.class)
class GraphAugmentationServiceTest {

    @Mock
    private HxprGraphService graphService;
    @Mock
    private HybridSearchService hybridSearchService;
    @Mock
    private HxprService hxprService;
    @Mock
    private SourceMetadataResolver sourceMetadataResolver;

    private RagProperties properties;
    private GraphAugmentationService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getGraph().setEnabled(true);
        service = new GraphAugmentationService(graphService, hybridSearchService, hxprService,
                sourceMetadataResolver, properties);
    }

    @Test
    void expandsSeedDocs_toRelatedDocs_reFilteredByPermission() {
        when(graphService.resolveGraphDbId(any(), eq("content-lake"))).thenReturn("gdb-1");
        // seed doc "sys-1" mentions entity "Acme Corp", which is also mentioned in "sys-2" (related).
        when(graphService.query(eq("gdb-1"), any(), any())).thenReturn(
                "{\"queryDocument\":[{\"documentId\":\"sys-1\",\"has_global_entity\":[{"
                        + "\"canonical_name\":\"Acme Corp\",\"mentioned_in\":[{\"documentId\":\"sys-1\"},"
                        + "{\"documentId\":\"sys-2\"}]}]}]}");
        when(hybridSearchService.buildCurrentUserPermissionFilter(any(), any()))
                .thenReturn("SELECT * FROM SysContent WHERE (acl) AND (sys_id = 'sys-2')");
        when(hxprService.query(any(), anyInt(), eq(0))).thenReturn(queryResult(doc("sys-2")));
        when(sourceMetadataResolver.resolveSourceDocument(eq("sys-2"), any()))
                .thenReturn(SourceDocument.builder().documentId("sys-2").name("Related.txt").build());

        GraphAugmentationService.Expansion exp = service.expand(List.of(seedHit("sys-1")), null, false);

        assertThat(exp.entities()).containsExactly("Acme Corp");
        assertThat(exp.graphHits()).hasSize(1);
        assertThat(exp.graphHits().get(0).getSourceDocument().getDocumentId()).isEqualTo("sys-2");

        // The permission filter must be scoped to the related id (sys-2), not the seed (sys-1).
        ArgumentCaptor<String> clause = ArgumentCaptor.forClass(String.class);
        verify(hybridSearchService).buildCurrentUserPermissionFilter(any(), clause.capture());
        assertThat(clause.getValue()).contains("sys-2").doesNotContain("sys-1");
    }

    @Test
    void includeCommunities_addsCommunitySummaryForTraversedEntity() {
        when(graphService.resolveGraphDbId(any(), eq("content-lake"))).thenReturn("gdb-1");
        // Seed doc sys-1 mentions "Acme Corp"; the entity is only in the seed (no related docs).
        when(graphService.query(eq("gdb-1"), any(), any())).thenReturn(
                "{\"queryDocument\":[{\"documentId\":\"sys-1\",\"has_global_entity\":[{"
                        + "\"canonical_name\":\"Acme Corp\",\"mentioned_in\":[{\"documentId\":\"sys-1\"}]}]}]}");
        // A community-summary doc for "Acme Corp" exists and passes the permission filter.
        when(hybridSearchService.buildCurrentUserPermissionFilter(any(), any()))
                .thenReturn("SELECT * FROM SysContent WHERE (acl) AND (sys_parentPath = '/_graph/communities')");
        HxprDocument comm = new HxprDocument();
        comm.setSysId("sys-comm");
        comm.setCinIngestProperties(Map.of("graph_community", "Acme Corp",
                "contentLake_extractedText", "Summary of activity related to Acme Corp."));
        when(hxprService.query(any(), anyInt(), eq(0))).thenReturn(queryResult(comm));
        when(sourceMetadataResolver.resolveSourceDocument(eq("sys-comm"), any()))
                .thenReturn(SourceDocument.builder().documentId("sys-comm").name("community-acme-corp").build());

        GraphAugmentationService.Expansion exp = service.expand(List.of(seedHit("sys-1")), null, true);

        assertThat(exp.entities()).contains("Acme Corp");
        assertThat(exp.graphHits()).hasSize(1);
        assertThat(exp.graphHits().get(0).getSourceDocument().getDocumentId()).isEqualTo("sys-comm");
        assertThat(exp.graphHits().get(0).getChunkText()).contains("Acme Corp");
    }

    @Test
    void returnsEmpty_whenGraphDbUnresolved() {
        when(graphService.resolveGraphDbId(any(), eq("content-lake"))).thenReturn(null);

        GraphAugmentationService.Expansion exp = service.expand(List.of(seedHit("sys-1")), null, false);

        assertThat(exp.graphHits()).isEmpty();
        assertThat(exp.entities()).isEmpty();
    }

    @Test
    void bestEffort_swallowsQueryFailure() {
        when(graphService.resolveGraphDbId(any(), eq("content-lake"))).thenReturn("gdb-1");
        when(graphService.query(eq("gdb-1"), any(), any())).thenThrow(new RuntimeException("boom"));

        GraphAugmentationService.Expansion exp = service.expand(List.of(seedHit("sys-1")), null, false);

        assertThat(exp.graphHits()).isEmpty();
    }

    private static SearchHit seedHit(String docId) {
        return SearchHit.builder()
                .sourceDocument(SourceDocument.builder().documentId(docId).build())
                .build();
    }

    private static HxprDocument doc(String sysId) {
        HxprDocument d = new HxprDocument();
        d.setSysId(sysId);
        d.setCinIngestProperties(Map.of("contentLake_extractedText", "related body text"));
        return d;
    }

    private static HxprDocument.QueryResult queryResult(HxprDocument... docs) {
        HxprDocument.QueryResult qr = new HxprDocument.QueryResult();
        qr.setDocuments(List.of(docs));
        return qr;
    }
}
