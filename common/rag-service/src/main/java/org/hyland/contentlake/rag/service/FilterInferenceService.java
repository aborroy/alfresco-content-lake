package org.hyland.contentlake.rag.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.HybridSearchRequest.MetadataFilter;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adaptive Retrieval: infers structured search filters from a natural-language question before
 * retrieval runs, so a question like "the latest HR policy from last year" narrows the candidate
 * set by modified date (and optionally category) instead of relying on the embedding alone.
 *
 * <p>Opt-in per request via {@code RagPromptRequest.inferFilters}. The inferred fields map onto the
 * existing {@link MetadataFilter} (mimeType, pathPrefix, modifiedAfter/Before, custom properties),
 * which {@code HybridSearchService.buildMetadataFilter} already turns into HXQL - and which runs
 * every custom-property value through {@code VocabularyService.resolve} for cross-repo label
 * normalisation. This service therefore adds no HXQL of its own.</p>
 *
 * <p>The LLM call goes through {@link StructuredLlmCaller}, so any failure degrades to "no inferred
 * filter" and retrieval proceeds unfiltered.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilterInferenceService {

    private static final String SYSTEM_PROMPT = """
            You extract structured search filters from a user's question about a document repository.
            Today's date is %s. Resolve relative dates ("last year", "since March", "recent") against
            it and return ISO-8601 calendar dates (YYYY-MM-DD).

            Only fill a field when the question clearly implies it; leave everything else null.
            - modifiedAfter / modifiedBefore: inclusive date bounds implied by the question.
            - mimeType: only when a concrete format is named (e.g. "PDF" -> application/pdf).
            - pathPrefix: only when the question names a concrete folder/path.
            - category: a single short topic label the question is scoped to (e.g. "hr", "finance"),
              or null if the question is not scoped to one category.

            Do not invent constraints the question does not state. If nothing applies, return an object
            with all fields null.""";

    private final StructuredLlmCaller structuredLlmCaller;
    private final RagProperties ragProperties;
    private final Clock clock;

    /**
     * Infers a {@link MetadataFilter} from the question, or returns {@code null} when nothing could
     * be inferred (or the call failed).
     */
    public MetadataFilter infer(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String system = SYSTEM_PROMPT.formatted(LocalDate.now(clock));
        InferredFilter inferred = structuredLlmCaller.call(
                system,
                "Question:\n" + question.trim(),
                InferredFilter.class,
                null,
                "filter inference");

        if (inferred == null) {
            return null;
        }
        return toMetadataFilter(inferred);
    }

    private MetadataFilter toMetadataFilter(InferredFilter inferred) {
        MetadataFilter.MetadataFilterBuilder builder = MetadataFilter.builder();
        boolean any = false;

        if (isPresent(inferred.getModifiedAfter())) {
            builder.modifiedAfter(inferred.getModifiedAfter().trim());
            any = true;
        }
        if (isPresent(inferred.getModifiedBefore())) {
            builder.modifiedBefore(inferred.getModifiedBefore().trim());
            any = true;
        }
        if (isPresent(inferred.getMimeType())) {
            builder.mimeType(inferred.getMimeType().trim());
            any = true;
        }
        if (isPresent(inferred.getPathPrefix())) {
            builder.pathPrefix(inferred.getPathPrefix().trim());
            any = true;
        }

        // A category is only usable if the deployment has told us which ingest property holds it.
        // Otherwise we would be guessing the property key, so the category is dropped. The value that
        // does get through is normalised against registered vocabularies downstream.
        String categoryProperty = ragProperties.getFilterInference().getCategoryProperty();
        if (isPresent(inferred.getCategory()) && isPresent(categoryProperty)) {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put(categoryProperty.trim(), inferred.getCategory().trim());
            builder.properties(properties);
            any = true;
        }

        if (!any) {
            return null;
        }
        MetadataFilter filter = builder.build();
        log.info("Inferred metadata filter: modifiedAfter={}, modifiedBefore={}, mimeType={}, "
                        + "pathPrefix={}, properties={}",
                filter.getModifiedAfter(), filter.getModifiedBefore(), filter.getMimeType(),
                filter.getPathPrefix(), filter.getProperties());
        return filter;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** Structured-output shape the LLM fills; mapped to a {@link MetadataFilter}. */
    @Data
    public static class InferredFilter {
        private String modifiedAfter;
        private String modifiedBefore;
        private String mimeType;
        private String pathPrefix;
        private String category;
    }
}
