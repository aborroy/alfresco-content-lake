package org.hyland.contentlake.service.chunking.strategy;

import org.hyland.contentlake.model.Chunk;

import java.util.List;

/**
 * Strategy for chunking document text.
 */
public interface ChunkingStrategy {

    /**
     * Returns a human-readable name for this strategy (stored in chunk metadata).
     */
    String strategyName();

    /**
     * Splits text into semantically coherent chunks.
     *
     * @param text   cleaned document text
     * @param nodeId Alfresco node ID (used for chunk ID generation)
     * @param config chunking parameters
     * @return ordered list of chunks
     */
    List<Chunk> chunk(String text, String nodeId, ChunkingConfig config);

    /**
     * Configuration parameters for chunking.
     */
    record ChunkingConfig(
            int minChunkSize,
            int maxChunkSize,
            int overlapSize,
            double similarityThreshold
    ) {
        public static ChunkingConfig defaults() {
            // 512-char max keeps CJK chunks within the embedding model's 512-token limit
            // (1 CJK char ≈ 1 token). Overlap raised to 150 to compensate for shorter windows.
            return new ChunkingConfig(200, 512, 150, 0.75);
        }
    }
}