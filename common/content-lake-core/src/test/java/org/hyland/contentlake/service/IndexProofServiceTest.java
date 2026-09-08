package org.hyland.contentlake.service;

import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.hxpr.api.model.Embedding;
import org.hyland.contentlake.hxpr.api.model.LocationModel;
import org.hyland.contentlake.hxpr.api.model.TextLocation;
import org.hyland.contentlake.hxpr.api.model.VectorSearchResult;
import org.hyland.contentlake.model.ContentLakeIngestProperties;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.model.IndexProof;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the instrument #114 adds: measured evidence that a node is retrievable, as opposed to the
 * {@code contentLake_syncStatus} a writer recorded. The status field can report {@code INDEXED} for a
 * document holding zero embeddings, so the verdicts here are derived from a chunk count taken off the
 * embeddings index rather than from any stored claim.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexProofServiceTest {

    private static final String NODE_ID = "node-1";
    private static final String SOURCE_ID = "alfresco:repo-uuid";
    private static final String DOC_ID = "hxpr-doc-1";

    @Mock
    private HxprService hxprService;
    @Mock
    private EmbeddingService embeddingService;

    private IndexProofService service;

    @BeforeEach
    void setUp() {
        service = new IndexProofService(hxprService, embeddingService);
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3));
    }

    @Test
    void absent_whenNoDocumentExistsForTheNode() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(null);

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5, null);

        assertThat(proof.verdict()).isEqualTo(IndexProof.Verdict.ABSENT);
        assertThat(proof.measured().exists()).isFalse();
        assertThat(proof.error()).isNull();
        // No embeddings query is worth issuing when there is no document to filter on.
        verify(hxprService, never()).vectorSearch(any(), any(), anyString(), anyInt());
    }

    @Test
    void metadataOnly_whenTheDocumentExistsButHoldsNoChunks() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 3));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(0L));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5, null);

        assertThat(proof.verdict()).isEqualTo(IndexProof.Verdict.METADATA_ONLY);
        assertThat(proof.measured().chunkCount()).isZero();
    }

    @Test
    void metadataOnly_showsTheMeasuredCountDisagreeingWithTheRecordedStatus() {
        // The failure #114 exists to expose: syncStatus says INDEXED and ingestion recorded a section
        // map with three chunks, but the embeddings index holds none, so the document is unreachable.
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 3));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(0L));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5, null);

        assertThat(proof.claimed().syncStatus()).isEqualTo("INDEXED");
        assertThat(proof.claimed().sectionMapChunks()).isEqualTo(3);
        assertThat(proof.measured().chunkCount()).isZero();
        assertThat(proof.verdict()).isEqualTo(IndexProof.Verdict.METADATA_ONLY);
    }

    @Test
    void indexedWithEmbeddings_whenTheEmbeddingsIndexHoldsChunks() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 3));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(
                List.of(new HxprService.EmbeddingChild("c1", "_e_ai-mxbai-embed-large", "ai-mxbai-embed-large")));
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt()))
                .thenReturn(searchResult(3L, chunk("e1", "ai-mxbai-embed-large", "first chunk", 1)));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5, null);

        assertThat(proof.verdict()).isEqualTo(IndexProof.Verdict.INDEXED_WITH_EMBEDDINGS);
        assertThat(proof.measured().chunkCount()).isEqualTo(3L);
        assertThat(proof.measured().documentId()).isEqualTo(DOC_ID);
        assertThat(proof.measured().chunkSample()).singleElement().satisfies(c -> {
            assertThat(c.chunkId()).isEqualTo("e1");
            assertThat(c.textPrefix()).isEqualTo("first chunk");
            assertThat(c.page()).isEqualTo(1);
        });
    }

    @Test
    void reportsEveryEmbeddingTypePresent_soAnOrphanFromARetiredModelIsVisible() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 3));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of(
                new HxprService.EmbeddingChild("c1", "_e_ai-mxbai-embed-large", "ai-mxbai-embed-large"),
                new HxprService.EmbeddingChild("c2", "_e_nomic-embed-text", "nomic-embed-text")));
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(6L));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5, null);

        assertThat(proof.measured().embeddingTypes())
                .containsExactly("ai-mxbai-embed-large", "nomic-embed-text");
        assertThat(proof.measured().embeddingChildren()).hasSize(2);
    }

    @Test
    void queriesEveryEmbeddingType_notOnlyTheConfiguredOne() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 3));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(1L));

        service.prove(NODE_ID, SOURCE_ID, 5, null);

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(hxprService).vectorSearch(any(), typeCaptor.capture(), filterCaptor.capture(), anyInt());
        // A null type makes hxpr substitute the * wildcard, so a chunk left behind by a retired model
        // is still counted (#113).
        assertThat(typeCaptor.getValue()).isNull();
        assertThat(filterCaptor.getValue()).contains("cin_id = '" + NODE_ID + "'");
    }

    @Test
    void degradesWithoutAVerdictWhenTheEmbeddingsQueryFails() {
        // POST /api/query/embeddings returns intermittent 500s. A guessed verdict would be worse than
        // none, because the whole point of this endpoint is that a confident status can be wrong.
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 3));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("all shards failed"));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5, null);

        assertThat(proof.verdict()).isNull();
        assertThat(proof.measured().chunkCount()).isNull();
        assertThat(proof.error()).contains("all shards failed");
        // The rest of the proof survives.
        assertThat(proof.measured().exists()).isTrue();
        assertThat(proof.claimed().syncStatus()).isEqualTo("INDEXED");
    }

    @Test
    void keepsTheChunkCountWhenOnlyTheChildEnumerationFails() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 3));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenThrow(new RuntimeException("query index down"));
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(3L));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5, null);

        assertThat(proof.verdict()).isEqualTo(IndexProof.Verdict.INDEXED_WITH_EMBEDDINGS);
        assertThat(proof.measured().embeddingChildren()).isEmpty();
    }

    @Test
    void boundsTheResponseRegardlessOfDocumentSize() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 2270));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(2270L,
                chunk("e1", "t", "a", null), chunk("e2", "t", "b", null), chunk("e3", "t", "c", null)));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 2, null);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(hxprService).vectorSearch(any(), any(), anyString(), limitCaptor.capture());
        // The limit is the sample size, not the chunk count, so a 2,270-chunk document costs the same
        // as a 3-chunk one. The total still reports the real figure.
        assertThat(limitCaptor.getValue()).isEqualTo(2);
        assertThat(proof.measured().chunkSample()).hasSize(2);
        assertThat(proof.measured().chunkCount()).isEqualTo(2270L);
    }

    @Test
    void clampsAnOversizedSampleRequest() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 3));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(3L));

        service.prove(NODE_ID, SOURCE_ID, 10_000, null);

        verify(hxprService).vectorSearch(any(), any(), anyString(), eq(IndexProofService.MAX_SAMPLE_SIZE));
    }

    @Test
    void truncatesChunkTextToAPrefix() {
        String long_ = "x".repeat(IndexProofService.TEXT_PREFIX_CHARS + 500);
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 1));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt()))
                .thenReturn(searchResult(1L, chunk("e1", "t", long_, null)));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5, null);

        assertThat(proof.measured().chunkSample().getFirst().textPrefix())
                .hasSize(IndexProofService.TEXT_PREFIX_CHARS);
    }

    @Test
    void embedsTheProbeOnceAndReusesIt() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 1));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(1L));

        service.prove(NODE_ID, SOURCE_ID, 5, null);
        service.prove(NODE_ID, SOURCE_ID, 5, null);

        // A diagnostic endpoint must not cost an embedding call per request.
        verify(embeddingService, times(1)).embed(anyString());
    }

    @Test
    void reportsTheSourceIdVariantTheDocumentWasActuallyFoundUnder() {
        HxprDocument doc = document("INDEXED", 1);
        // Documents indexed before the type:rawId format carry the bare raw id.
        doc.setCinSourceId("repo-uuid");
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(doc);
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(1L));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5, null);

        assertThat(proof.measured().cinSourceId()).isEqualTo("repo-uuid");
    }

    @Test
    void carriesTheSourceSideClaimThrough() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("PENDING", 0));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(0L));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5,
                IndexProofService.NodeClaims.resolved("INDEXED"));

        // The source node and hxpr disagreeing is itself diagnostic, so both are reported.
        assertThat(proof.claimed().nodeSyncStatus()).isEqualTo("INDEXED");
        assertThat(proof.claimed().syncStatus()).isEqualTo("PENDING");
    }

    @Test
    void measuresTheIndexEvenWhenTheSourceNodeCouldNotBeResolved_butWithholdsChunkText() {
        // A document whose source node is gone is the phantom result reconciliation exists to remove,
        // so reporting ABSENT without looking at the index would hide exactly the case worth finding.
        // The chunk sample is withheld instead, because the caller's right to read that content could
        // not be established.
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 3));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt()))
                .thenReturn(searchResult(3L, chunk("e1", "t", "secret chunk text", 1)));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5, IndexProofService.NodeClaims.unresolved());

        assertThat(proof.verdict()).isEqualTo(IndexProof.Verdict.INDEXED_WITH_EMBEDDINGS);
        assertThat(proof.measured().chunkCount()).isEqualTo(3L);
        assertThat(proof.measured().chunkSample()).isEmpty();
        assertThat(proof.claimed().sourceNodeResolved()).isFalse();
    }

    @Test
    void reportsTheSourceNodeAsResolvedWhenTheAdapterReadIt() {
        when(hxprService.findByNodeId(NODE_ID, SOURCE_ID)).thenReturn(document("INDEXED", 1));
        when(hxprService.listEmbeddingChildren(DOC_ID)).thenReturn(List.of());
        when(hxprService.vectorSearch(any(), any(), anyString(), anyInt())).thenReturn(searchResult(1L));

        IndexProof proof = service.prove(NODE_ID, SOURCE_ID, 5,
                IndexProofService.NodeClaims.resolved("INDEXED"));

        assertThat(proof.claimed().sourceNodeResolved()).isTrue();
    }

    private static HxprDocument document(String syncStatus, int sectionMapChunks) {
        HxprDocument doc = new HxprDocument();
        doc.setSysId(DOC_ID);
        doc.setCinSourceId(SOURCE_ID);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put(ContentLakeIngestProperties.CONTENT_LAKE_SYNC_STATUS, syncStatus);
        props.put(ContentLakeIngestProperties.SOURCE_MODIFIED_AT, "2026-09-01T10:00:00Z");
        if (sectionMapChunks > 0) {
            props.put(ContentLakeIngestProperties.CONTENT_LAKE_SECTION_MAP,
                    sectionMapJson(sectionMapChunks));
        }
        doc.setCinIngestProperties(props);
        return doc;
    }

    private static String sectionMapJson(int chunks) {
        StringBuilder sections = new StringBuilder();
        for (int i = 0; i < chunks; i++) {
            sections.append(i > 0 ? ",0" : "0");
        }
        return "{\"chunkSections\":[" + sections + "],"
                + "\"sections\":[{\"index\":0,\"type\":\"PROSE\",\"text\":\"body\"}]}";
    }

    private static VectorSearchResult searchResult(Long totalCount, Embedding... embeddings) {
        VectorSearchResult result = new VectorSearchResult();
        result.setTotalCount(totalCount);
        result.setCount((long) embeddings.length);
        result.setTotalCountIsTruncated(false);
        result.setEmbeddings(List.of(embeddings));
        return result;
    }

    private static Embedding chunk(String id, String type, String text, Integer page) {
        Embedding embedding = new Embedding();
        embedding.setSysembedId(id);
        embedding.setSysembedType(type);
        embedding.setSysembedText(text);
        if (page != null) {
            TextLocation textLocation = new TextLocation();
            textLocation.setPage(page);
            LocationModel location = new LocationModel();
            location.setText(textLocation);
            embedding.setSysembedLocation(location);
        }
        return embedding;
    }
}
