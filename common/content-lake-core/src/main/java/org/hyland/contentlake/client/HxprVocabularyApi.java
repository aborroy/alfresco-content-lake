package org.hyland.contentlake.client;

import org.hyland.contentlake.hxpr.api.model.VocabularyEntry;
import org.hyland.contentlake.hxpr.api.model.VocabularyId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.List;

/**
 * Spring HTTP Interface for the HXPR Vocabulary REST API.
 * <p>
 * Read-only surface used for cross-repo metadata normalization: list the registered
 * vocabularies, then list a vocabulary's entries to resolve a human {@code sysvocab_label}
 * to its canonical {@code sysvocab_key} before building HXQL filters.
 * <p>
 * Auth and {@code HXCS-REPOSITORY} headers are injected automatically by the interceptor
 * configured on the underlying {@code RestClient}.
 */
@HttpExchange("/api/vocabularies")
public interface HxprVocabularyApi {

    /** Returns the ids of all registered vocabularies (or empty). */
    @GetExchange
    List<VocabularyId> getVocabularyList();

    /** Returns all entries (key + label) of a vocabulary, enabling label to key resolution. */
    @GetExchange("/{sysvocab_id}")
    List<VocabularyEntry> getVocabularyEntries(@PathVariable("sysvocab_id") String vocabularyId);
}
