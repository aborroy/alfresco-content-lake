package org.hyland.contentlake.rag.model;

/**
 * Requested shape of a RAG answer (#70).
 *
 * <ul>
 *   <li>{@link #TEXT} - free-text answer only (default; unchanged behavior).</li>
 *   <li>{@link #STRUCTURED} - additionally return a typed {@link StructuredAnswer} alongside the
 *       free-text answer, derived in a second pass so existing callers are unaffected.</li>
 * </ul>
 */
public enum ResponseFormat {
    TEXT,
    STRUCTURED
}
