package org.hyland.contentlake.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.model.ContentLakeIngestProperties;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.model.SectionMap;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small-to-big (parent-child) retrieval: expands each retrieved small chunk to its full parent
 * section before the context is assembled for the LLM.
 *
 * <p>Chunks are stored flat as rows in one Parquet embeddings child, with no section-level hxpr
 * node and no ancestors endpoint. Rather than introduce those, this expands in-process using the
 * per-document section map that ingestion writes to {@code cin_ingestProperties}: the matched
 * chunk's paragraph index selects a section, and that section's stored text replaces the chunk text
 * used for context. Multiple hits from the same section collapse to one expanded entry.</p>
 *
 * <p>Off by default ({@code rag.retrieval.small-to-big.enabled}). Expansion is applied only to the
 * context copy handed to prompt assembly; the original hits (and therefore the response's source
 * citations) are left untouched. Any document without a section map, or any lookup failure, leaves
 * that hit as-is.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SectionExpansionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECTION_MAP_KEY = ContentLakeIngestProperties.CONTENT_LAKE_SECTION_MAP;

    private final HxprDocumentApi documentApi;
    private final RagProperties ragProperties;

    public boolean isEnabled() {
        return ragProperties.getRetrieval().getSmallToBig().isEnabled();
    }

    /**
     * Returns a context-assembly view of {@code hits} with each chunk expanded to its parent section.
     * Returns the input unchanged when disabled or empty. The returned hits are copies; callers must
     * not assume identity with the input.
     */
    public List<SearchHit> expandForContext(List<SearchHit> hits) {
        if (!isEnabled() || hits == null || hits.isEmpty()) {
            return hits;
        }

        int maxChars = ragProperties.getRetrieval().getSmallToBig().getMaxSectionChars();
        Map<String, SectionMap> mapCache = new HashMap<>();
        Set<String> expandedSections = new HashSet<>();
        List<SearchHit> result = new ArrayList<>(hits.size());

        for (SearchHit hit : hits) {
            String documentId = hit.getSourceDocument() != null ? hit.getSourceDocument().getDocumentId() : null;
            Integer paragraph = hit.getChunkMetadata() != null ? hit.getChunkMetadata().getParagraph() : null;
            if (documentId == null || paragraph == null) {
                result.add(hit);
                continue;
            }

            SectionMap map = mapCache.computeIfAbsent(documentId, this::loadSectionMap);
            Integer sectionIndex = sectionForChunk(map, paragraph);
            if (sectionIndex == null) {
                result.add(hit);
                continue;
            }

            String dedupeKey = documentId + ":" + sectionIndex;
            if (!expandedSections.add(dedupeKey)) {
                // Another hit already contributed this whole section; drop the duplicate from context.
                continue;
            }

            String sectionText = sectionText(map, sectionIndex);
            if (sectionText == null || sectionText.isBlank()) {
                result.add(hit);
                continue;
            }
            if (sectionText.length() > maxChars) {
                sectionText = sectionText.substring(0, maxChars);
            }
            result.add(copyWithText(hit, sectionText));
        }

        return result;
    }

    private SectionMap loadSectionMap(String documentId) {
        try {
            HxprDocument doc = documentApi.getById(documentId);
            if (doc == null || doc.getCinIngestProperties() == null) {
                return null;
            }
            Object json = doc.getCinIngestProperties().get(SECTION_MAP_KEY);
            if (json == null) {
                return null;
            }
            return MAPPER.readValue(json.toString(), SectionMap.class);
        } catch (Exception e) {
            log.warn("Failed to load section map for document {}: {}", documentId, e.getMessage());
            return null;
        }
    }

    private Integer sectionForChunk(SectionMap map, int chunkIndex) {
        if (map == null || map.chunkSections() == null
                || chunkIndex < 0 || chunkIndex >= map.chunkSections().size()) {
            return null;
        }
        return map.chunkSections().get(chunkIndex);
    }

    private String sectionText(SectionMap map, int sectionIndex) {
        if (map.sections() == null) {
            return null;
        }
        return map.sections().stream()
                .filter(s -> s.index() == sectionIndex)
                .map(SectionMap.Section::text)
                .findFirst()
                .orElse(null);
    }

    private SearchHit copyWithText(SearchHit hit, String text) {
        return SearchHit.builder()
                .rank(hit.getRank())
                .score(hit.getScore())
                .chunkText(text)
                .sourceDocument(hit.getSourceDocument())
                .chunkMetadata(hit.getChunkMetadata())
                .vector(hit.getVector())
                .build();
    }
}
