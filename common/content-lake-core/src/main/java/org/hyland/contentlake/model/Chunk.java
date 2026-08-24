package org.hyland.contentlake.model;

import lombok.Data;

@Data
public class Chunk {

    private String id;
    private String nodeId;
    private String text;
    private int index;
    private int startOffset;
    private int endOffset;

    /** Name of the chunking strategy that produced this chunk. */
    private String chunkingStrategy;

    /** Structural classification (prose vs table); defaults to {@link ChunkType#PROSE}. */
    private ChunkType chunkType = ChunkType.PROSE;

    /**
     * Zero-based index of the source section this chunk originated from.
     *
     * <p>Chunks sharing a {@code sectionIndex} came from the same heading-delimited section (or the
     * same table), which is what small-to-big retrieval expands a hit back out to.</p>
     */
    private int sectionIndex;

    public Chunk(String nodeId, String text, int index, int startOffset, int endOffset) {
        this.id = nodeId + "_chunk_" + index;
        this.nodeId = nodeId;
        this.text = text;
        this.index = index;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public Chunk(String nodeId, String text, int index, int startOffset, int endOffset,
                 String chunkingStrategy) {
        this(nodeId, text, index, startOffset, endOffset);
        this.chunkingStrategy = chunkingStrategy;
    }
}
