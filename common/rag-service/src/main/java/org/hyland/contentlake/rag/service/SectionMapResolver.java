package org.hyland.contentlake.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.model.ContentLakeIngestProperties;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.model.SectionMap;
import org.springframework.stereotype.Service;

/**
 * Resolves a chunk's {@link org.hyland.contentlake.model.ChunkType} (PROSE/TABLE) from the
 * per-document section map that ingestion writes to {@code cin_ingestProperties.contentLake_sectionMap}.
 *
 * <p>chunkType is persisted only at section granularity in that map (as {@code Section.type}), not
 * per embedding, so retrieval reads it by mapping a hit's chunk index (the embedding's paragraph
 * location) to its section and returning that section's type. Any document without a section map
 * (e.g. one whose map exceeded the ingest size cap) resolves to {@code null}.</p>
 */
@Slf4j
@Service
public class SectionMapResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECTION_MAP_KEY = ContentLakeIngestProperties.CONTENT_LAKE_SECTION_MAP;

    /** Parses the section map from an already-fetched document, or {@code null} when absent/invalid. */
    public SectionMap parse(HxprDocument doc) {
        if (doc == null || doc.getCinIngestProperties() == null) {
            return null;
        }
        Object json = doc.getCinIngestProperties().get(SECTION_MAP_KEY);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json.toString(), SectionMap.class);
        } catch (Exception e) {
            log.warn("Failed to parse section map: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the chunk's type name (e.g. {@code "PROSE"} / {@code "TABLE"}) for the given chunk
     * index, or {@code null} when the map is absent or the index is out of range.
     *
     * @param map      the document's section map (may be null)
     * @param chunkIndex the embedding's paragraph location (chunk index)
     */
    public String chunkType(SectionMap map, Integer chunkIndex) {
        if (map == null || chunkIndex == null
                || map.chunkSections() == null || map.sections() == null
                || chunkIndex < 0 || chunkIndex >= map.chunkSections().size()) {
            return null;
        }
        Integer sectionIndex = map.chunkSections().get(chunkIndex);
        if (sectionIndex == null) {
            return null;
        }
        return map.sections().stream()
                .filter(s -> s.index() == sectionIndex)
                .map(SectionMap.Section::type)
                .findFirst()
                .orElse(null);
    }
}
