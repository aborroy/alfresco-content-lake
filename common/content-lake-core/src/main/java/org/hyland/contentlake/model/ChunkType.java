package org.hyland.contentlake.model;

/**
 * Structural classification of a {@link Chunk}.
 *
 * <p>Tabular content must survive chunking intact (rows/columns aligned) rather than being treated
 * as prose noise or hard-split mid-row. Marking a chunk {@link #TABLE} lets the noise-reduction and
 * chunking stages preserve it, and lets downstream context assembly render it distinctly if needed.</p>
 */
public enum ChunkType {

    /** Ordinary running text. */
    PROSE,

    /** A detected table (markdown/pipe-delimited), kept as an atomic unit. */
    TABLE
}
