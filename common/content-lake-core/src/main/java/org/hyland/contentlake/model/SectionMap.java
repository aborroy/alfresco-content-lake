package org.hyland.contentlake.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Per-document section map persisted in {@code cin_ingestProperties} to support small-to-big
 * (parent-child) retrieval over the flat, single-Parquet chunk store.
 *
 * <p>{@code chunkSections} is indexed by chunk index (the value stored as an embedding's paragraph
 * location) and yields that chunk's section index. {@code sections} carries each section's text and
 * type, so a matched small chunk can be expanded to its full parent section without an ancestors API
 * call or a Parquet re-read.</p>
 *
 * @param chunkSections section index for each chunk, indexed by chunk index
 * @param sections      the sections referenced by {@code chunkSections}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SectionMap(List<Integer> chunkSections, List<Section> sections) {

    /**
     * @param index zero-based section index
     * @param type  {@link ChunkType} name (PROSE/TABLE)
     * @param text  the section's full text (concatenated chunk text)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(int index, String type, String text) {
    }
}
