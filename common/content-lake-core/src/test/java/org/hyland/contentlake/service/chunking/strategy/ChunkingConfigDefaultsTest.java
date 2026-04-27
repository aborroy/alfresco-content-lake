package org.hyland.contentlake.service.chunking.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingConfigDefaultsTest {

    @Test
    void defaults_maxChunkSize_is512() {
        assertThat(ChunkingStrategy.ChunkingConfig.defaults().maxChunkSize()).isEqualTo(512);
    }

    @Test
    void defaults_overlapSize_is150() {
        assertThat(ChunkingStrategy.ChunkingConfig.defaults().overlapSize()).isEqualTo(150);
    }

    @Test
    void defaults_minChunkSize_unchanged() {
        assertThat(ChunkingStrategy.ChunkingConfig.defaults().minChunkSize()).isEqualTo(200);
    }

    @Test
    void defaults_similarityThreshold_unchanged() {
        assertThat(ChunkingStrategy.ChunkingConfig.defaults().similarityThreshold()).isEqualTo(0.75);
    }

    @Test
    void defaults_overlapSmallerThanMaxChunkSize() {
        ChunkingStrategy.ChunkingConfig cfg = ChunkingStrategy.ChunkingConfig.defaults();
        assertThat(cfg.overlapSize()).isLessThan(cfg.maxChunkSize());
    }

    @Test
    void defaults_maxChunkSizeWithinEmbeddingModelLimit() {
        // The embedding model has a 512-token hard limit.
        // CJK text is ~1 char/token, so maxChunkSize must be ≤ 512.
        assertThat(ChunkingStrategy.ChunkingConfig.defaults().maxChunkSize()).isLessThanOrEqualTo(512);
    }
}
