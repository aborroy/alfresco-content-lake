package org.hyland.contentlake.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.hxpr.api.model.VocabularyEntry;
import org.hyland.contentlake.hxpr.api.model.VocabularyId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves human metadata labels to their canonical vocabulary keys, so filters match across repos
 * that store the same concept under different spellings (e.g. {@code "HR Documents"}, {@code "hr-docs"},
 * {@code "Human Resources"} all resolving to one key).
 *
 * <p>Resolution is best-effort: a value that matches a registered {@code sysvocab_label} is replaced
 * with its {@code sysvocab_key}; anything else (including values that are already canonical keys)
 * passes through unchanged. The label to key map is built from all registered vocabularies and cached;
 * if hxpr cannot be reached the map is empty and every value passes through, so a vocabulary outage
 * degrades to today's verbatim behavior rather than failing the search.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class VocabularyService {

    private final HxprVocabularyApi vocabularyApi;

    /** Cached label to canonical-key map across all vocabularies; {@code null} until first load. */
    private volatile Map<String, String> labelToKey;

    /**
     * Returns the canonical key for a metadata label, or the value unchanged when it is blank,
     * unknown, or already canonical.
     */
    public String resolve(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return labelToKey().getOrDefault(value, value);
    }

    /** Drops the cached map so the next {@link #resolve(String)} reloads vocabularies from hxpr. */
    public void invalidate() {
        this.labelToKey = null;
    }

    private Map<String, String> labelToKey() {
        Map<String, String> map = labelToKey;
        if (map == null) {
            synchronized (this) {
                map = labelToKey;
                if (map == null) {
                    map = loadLabelToKey();
                    labelToKey = map;
                }
            }
        }
        return map;
    }

    private Map<String, String> loadLabelToKey() {
        Map<String, String> map = new HashMap<>();
        try {
            List<VocabularyId> vocabularies = vocabularyApi.getVocabularyList();
            if (vocabularies == null) {
                return map;
            }
            for (VocabularyId vocabulary : vocabularies) {
                String id = vocabulary.getSysvocabId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                List<VocabularyEntry> entries = vocabularyApi.getVocabularyEntries(id);
                if (entries == null) {
                    continue;
                }
                for (VocabularyEntry entry : entries) {
                    if (entry.getSysvocabLabel() != null && entry.getSysvocabKey() != null) {
                        // First registration wins on cross-vocabulary label collisions.
                        map.putIfAbsent(entry.getSysvocabLabel(), entry.getSysvocabKey());
                    }
                }
            }
            log.debug("Loaded {} vocabulary label->key mappings", map.size());
        } catch (Exception e) {
            log.warn("Failed to load vocabularies; metadata values pass through unresolved: {}", e.getMessage());
        }
        return map;
    }
}
