package org.hyland.contentlake.service;

import org.hyland.contentlake.client.HxprGraphService;
import org.hyland.contentlake.config.HxprProperties;
import org.hyland.contentlake.model.graph.GraphEntityUpsert;
import org.hyland.contentlake.model.graph.GraphRelationshipUpsert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #54: extraction feeds the graph as GlobalEntities linked to the Document, and is best-effort.
 */
@ExtendWith(MockitoExtension.class)
class GraphIngestionServiceTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private HxprGraphService graphService;

    private HxprProperties properties;
    private GraphIngestionService service;

    @BeforeEach
    void setUp() {
        properties = new HxprProperties();
        properties.getGraph().setEnabled(true);
        properties.getGraph().setExtractionEnabled(true);
        service = new GraphIngestionService(chatModel, graphService, properties);
    }

    @Test
    void extractsEntities_upsertsGlobalEntities_andLinksToDocument() {
        when(graphService.resolveGraphDbId(any(), eq("content-lake"))).thenReturn("gdb-1");
        chatReturns("{\"entities\":[{\"name\":\"Acme Corp\",\"type\":\"Organization\",\"aliases\":[\"Acme\"]}," +
                "{\"name\":\"Jane Roe\",\"type\":\"Person\",\"aliases\":[]}]}");
        when(graphService.upsertEntities(eq("gdb-1"), anyList()))
                .thenReturn(Map.of("doc", "0xdoc", "e0", "0xe0", "e1", "0xe1"));

        List<String> names = service.ingest("sys-1", "Acme Corp hired Jane Roe.", "memo.txt");

        assertThat(names).containsExactlyInAnyOrder("Acme Corp", "Jane Roe");

        // Document entity + two GlobalEntity entities upserted, all carrying documentId.
        ArgumentCaptor<List<GraphEntityUpsert>> entCap = ArgumentCaptor.forClass(List.class);
        verify(graphService).upsertEntities(eq("gdb-1"), entCap.capture());
        List<GraphEntityUpsert> ents = entCap.getValue();
        assertThat(ents).hasSize(3);
        assertThat(ents.get(0).getType()).isEqualTo("Document");
        assertThat(ents.get(0).getProperties()).containsEntry("documentId", "sys-1");
        assertThat(ents.get(1).getType()).isEqualTo("GlobalEntity");
        assertThat(ents.get(1).getProperties()).containsEntry("entity_type", "Organization");

        // Two has_global_entity edges from the Document uid to the entity uids.
        ArgumentCaptor<List<GraphRelationshipUpsert>> relCap = ArgumentCaptor.forClass(List.class);
        verify(graphService).upsertRelationships(eq("gdb-1"), relCap.capture());
        List<GraphRelationshipUpsert> rels = relCap.getValue();
        assertThat(rels).hasSize(2);
        assertThat(rels).allSatisfy(r -> {
            assertThat(r.getSourceUid()).isEqualTo("0xdoc");
            assertThat(r.getRelationshipType()).isEqualTo("has_global_entity");
            assertThat(r.getSourceType()).isEqualTo("Document");
            assertThat(r.getTargetType()).isEqualTo("GlobalEntity");
        });
    }

    @Test
    void reusesExistingEntity_insteadOfCreatingDuplicate() {
        when(graphService.resolveGraphDbId(any(), eq("content-lake"))).thenReturn("gdb-1");
        chatReturns("{\"entities\":[{\"name\":\"Acme Corp\",\"type\":\"Organization\",\"aliases\":[]}]}");
        // Entity resolution finds an existing GlobalEntity for "Acme Corp".
        when(graphService.query(eq("gdb-1"), any(), any())).thenReturn(
                "{\"queryGlobalEntity\":[{\"uid\":\"0xexisting\",\"canonical_name\":\"Acme Corp\"}]}");
        // Only the Document is upserted (no new GlobalEntity).
        when(graphService.upsertEntities(eq("gdb-1"), anyList())).thenReturn(Map.of("doc", "0xdoc"));

        service.ingest("sys-9", "Acme Corp again.", "n.txt");

        ArgumentCaptor<List<GraphEntityUpsert>> entCap = ArgumentCaptor.forClass(List.class);
        verify(graphService).upsertEntities(eq("gdb-1"), entCap.capture());
        assertThat(entCap.getValue()).hasSize(1);  // Document only; entity reused
        assertThat(entCap.getValue().get(0).getType()).isEqualTo("Document");

        // The document links to the pre-existing entity uid.
        ArgumentCaptor<List<GraphRelationshipUpsert>> relCap = ArgumentCaptor.forClass(List.class);
        verify(graphService).upsertRelationships(eq("gdb-1"), relCap.capture());
        assertThat(relCap.getValue()).hasSize(1);
        assertThat(relCap.getValue().get(0).getTargetUid()).isEqualTo("0xexisting");
    }

    @Test
    void skips_whenGraphDbCannotBeResolved() {
        when(graphService.resolveGraphDbId(any(), eq("content-lake"))).thenReturn(null);

        List<String> names = service.ingest("sys-1", "text", "n.txt");

        assertThat(names).isEmpty();
        verify(graphService, never()).upsertEntities(any(), anyList());
    }

    @Test
    void skips_whenExtractionDisabled() {
        properties.getGraph().setExtractionEnabled(false);

        List<String> names = service.ingest("sys-1", "text", "n.txt");

        assertThat(names).isEmpty();
        verify(graphService, never()).resolveGraphDbId(any(), any());
    }

    @Test
    void bestEffort_swallowsUpsertFailure() {
        when(graphService.resolveGraphDbId(any(), eq("content-lake"))).thenReturn("gdb-1");
        chatReturns("{\"entities\":[{\"name\":\"Acme\",\"type\":\"Organization\",\"aliases\":[]}]}");
        when(graphService.upsertEntities(eq("gdb-1"), anyList())).thenThrow(new RuntimeException("boom"));

        List<String> names = service.ingest("sys-1", "Acme.", "n.txt");

        assertThat(names).isEmpty();  // failure degrades to no-op, does not throw
    }

    private void chatReturns(String json) {
        ChatResponse resp = new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        when(chatModel.call(any(Prompt.class))).thenReturn(resp);
    }
}
