package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.model.ContentLakeIngestProperties;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SourceDocument;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.ChunkMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SectionExpansionServiceTest {

    @Mock
    HxprDocumentApi documentApi;

    private RagProperties properties;
    private SectionExpansionService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        service = new SectionExpansionService(documentApi, properties);
    }

    private void enable() {
        properties.getRetrieval().getSmallToBig().setEnabled(true);
    }

    private SearchHit hit(String docId, int paragraph, String chunkText) {
        return SearchHit.builder()
                .rank(1)
                .score(0.9)
                .chunkText(chunkText)
                .sourceDocument(SourceDocument.builder().documentId(docId).name("doc").build())
                .chunkMetadata(ChunkMetadata.builder().paragraph(paragraph).build())
                .build();
    }

    private void stubSectionMap(String docId, String json) {
        HxprDocument doc = new HxprDocument();
        doc.setCinIngestProperties(Map.of(ContentLakeIngestProperties.CONTENT_LAKE_SECTION_MAP, json));
        lenient().when(documentApi.getById(docId)).thenReturn(doc);
    }

    @Test
    void disabled_returnsInputUnchanged() {
        List<SearchHit> hits = List.of(hit("d1", 0, "small chunk"));

        assertThat(service.expandForContext(hits)).isSameAs(hits);
        verifyNoInteractions(documentApi);
    }

    @Test
    void expandsChunkToParentSectionText() {
        enable();
        // chunk index 1 belongs to section 0 whose full text is "big section text".
        stubSectionMap("d1", """
                {"chunkSections":[0,0],"sections":[{"index":0,"type":"PROSE","text":"big section text"}]}""");

        List<SearchHit> out = service.expandForContext(List.of(hit("d1", 1, "small chunk")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getChunkText()).isEqualTo("big section text");
    }

    @Test
    void collapsesMultipleHitsFromSameSection() {
        enable();
        stubSectionMap("d1", """
                {"chunkSections":[0,0],"sections":[{"index":0,"type":"PROSE","text":"big section text"}]}""");

        List<SearchHit> out = service.expandForContext(List.of(
                hit("d1", 0, "chunk a"),
                hit("d1", 1, "chunk b")));

        // Both chunks map to section 0; only one expanded entry survives.
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getChunkText()).isEqualTo("big section text");
    }

    @Test
    void missingSectionMap_leavesHitUnchanged() {
        enable();
        when(documentApi.getById("d1")).thenReturn(new HxprDocument());

        List<SearchHit> out = service.expandForContext(List.of(hit("d1", 0, "small chunk")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getChunkText()).isEqualTo("small chunk");
    }

    @Test
    void truncatesToMaxSectionChars() {
        enable();
        properties.getRetrieval().getSmallToBig().setMaxSectionChars(5);
        stubSectionMap("d1", """
                {"chunkSections":[0],"sections":[{"index":0,"type":"PROSE","text":"abcdefghij"}]}""");

        List<SearchHit> out = service.expandForContext(List.of(hit("d1", 0, "x")));

        assertThat(out.get(0).getChunkText()).isEqualTo("abcde");
    }
}
