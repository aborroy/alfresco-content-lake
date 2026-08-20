package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.model.SemanticSearchResponse.ChunkMetadata;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SourceDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    private static SearchHit hit(String docId, String embeddingId, int rank, double score) {
        return SearchHit.builder()
                .rank(rank)
                .score(score)
                .chunkText(docId + "/" + embeddingId)
                .sourceDocument(SourceDocument.builder().documentId(docId).build())
                .chunkMetadata(ChunkMetadata.builder().embeddingId(embeddingId).build())
                .build();
    }

    @Test
    void fuse_chunkFoundByTwoVariants_outranksOneFoundByASingleVariant() {
        // "shared" is second for both variants; "solo" is first for one of them and absent from the
        // other. Two second places beat one first place, which is the whole point of RRF.
        List<SearchHit> variantA = List.of(hit("d1", "e1", 1, 0.9), hit("d2", "e2", 2, 0.8));
        List<SearchHit> variantB = List.of(hit("d3", "e3", 1, 0.7), hit("d2", "e2", 2, 0.6));

        List<SearchHit> fused = RrfFusion.fuse(List.of(variantA, variantB), 60, 0);

        assertThat(fused).extracting(h -> h.getSourceDocument().getDocumentId())
                .containsExactly("d2", "d1", "d3");
    }

    @Test
    void fuse_reassignsRanksAndPreservesTheOriginalScore() {
        List<SearchHit> variantA = List.of(hit("d1", "e1", 5, 0.42));

        List<SearchHit> fused = RrfFusion.fuse(List.of(variantA), 60, 0);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).getRank()).isEqualTo(1);
        // Order comes from the RRF score; the displayed score stays on the retrieval scale.
        assertThat(fused.get(0).getScore()).isEqualTo(0.42);
    }

    @Test
    void fuse_deduplicatesOnDocumentAndEmbeddingId() {
        List<SearchHit> variantA = List.of(hit("d1", "e1", 1, 0.9));
        List<SearchHit> variantB = List.of(hit("d1", "e1", 1, 0.5));

        assertThat(RrfFusion.fuse(List.of(variantA, variantB), 60, 0)).hasSize(1);
    }

    @Test
    void fuse_differentChunksOfTheSameDocumentStaySeparate() {
        List<SearchHit> variantA = List.of(hit("d1", "e1", 1, 0.9), hit("d1", "e2", 2, 0.8));

        assertThat(RrfFusion.fuse(List.of(variantA), 60, 0)).hasSize(2);
    }

    @Test
    void fuse_missingIds_fallsBackToChunkTextRatherThanCollapsingHits() {
        SearchHit a = SearchHit.builder().rank(1).score(0.9).chunkText("alpha").build();
        SearchHit b = SearchHit.builder().rank(2).score(0.8).chunkText("beta").build();
        SearchHit alphaAgain = SearchHit.builder().rank(1).score(0.7).chunkText("alpha").build();

        List<SearchHit> fused = RrfFusion.fuse(List.of(List.of(a, b), List.of(alphaAgain)), 60, 0);

        assertThat(fused).hasSize(2);
        assertThat(fused).extracting(SearchHit::getChunkText).containsExactly("alpha", "beta");
    }

    @Test
    void fuse_usesListPositionWhenARankIsUnset() {
        SearchHit first = SearchHit.builder().score(0.9).chunkText("first").build();
        SearchHit second = SearchHit.builder().score(0.8).chunkText("second").build();

        List<SearchHit> fused = RrfFusion.fuse(List.of(List.of(first, second)), 60, 0);

        assertThat(fused).extracting(SearchHit::getChunkText).containsExactly("first", "second");
    }

    @Test
    void fuse_appliesTheLimit() {
        List<SearchHit> variantA = List.of(hit("d1", "e1", 1, 0.9), hit("d2", "e2", 2, 0.8), hit("d3", "e3", 3, 0.7));

        assertThat(RrfFusion.fuse(List.of(variantA), 60, 2)).hasSize(2);
        assertThat(RrfFusion.fuse(List.of(variantA), 60, 0)).hasSize(3);
    }

    @Test
    void fuse_toleratesNullAndEmptyInput() {
        assertThat(RrfFusion.fuse(null, 60, 0)).isEmpty();
        assertThat(RrfFusion.fuse(List.of(), 60, 0)).isEmpty();
        assertThat(RrfFusion.fuse(List.of(List.of()), 60, 0)).isEmpty();
    }

    @Test
    void fuse_largerKFlattensTheAdvantageOfTopRanks() {
        // Rank 1 in one list versus ranks 3 and 4 in two others. At k=1 the single first place wins;
        // at k=60 the two mid-ranked appearances do.
        List<SearchHit> a = List.of(hit("solo", "e", 1, 0.9));
        List<SearchHit> b = List.of(hit("shared", "e", 3, 0.5));
        List<SearchHit> c = List.of(hit("shared", "e", 4, 0.5));

        assertThat(RrfFusion.fuse(List.of(a, b, c), 1, 0))
                .first().extracting(h -> h.getSourceDocument().getDocumentId()).isEqualTo("solo");
        assertThat(RrfFusion.fuse(List.of(a, b, c), 60, 0))
                .first().extracting(h -> h.getSourceDocument().getDocumentId()).isEqualTo("shared");
    }
}
