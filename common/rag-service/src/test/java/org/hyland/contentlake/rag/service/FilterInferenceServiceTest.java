package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.HybridSearchRequest.MetadataFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FilterInferenceServiceTest {

    @Mock
    StructuredLlmCaller structuredLlmCaller;

    private RagProperties properties;
    private FilterInferenceService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        Clock fixed = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);
        service = new FilterInferenceService(structuredLlmCaller, properties, fixed);
    }

    private FilterInferenceService.InferredFilter inferred(String after, String mime, String category) {
        FilterInferenceService.InferredFilter f = new FilterInferenceService.InferredFilter();
        f.setModifiedAfter(after);
        f.setMimeType(mime);
        f.setCategory(category);
        return f;
    }

    @Test
    void blankQuestion_returnsNullWithoutCallingLlm() {
        assertThat(service.infer("  ")).isNull();
    }

    @Test
    void extractsDateAndMime() {
        when(structuredLlmCaller.call(any(), eq("Question:\nPDF policies from last year"),
                eq(FilterInferenceService.InferredFilter.class), any(), any()))
                .thenReturn(inferred("2025-01-01", "application/pdf", null));

        MetadataFilter filter = service.infer("PDF policies from last year");

        assertThat(filter).isNotNull();
        assertThat(filter.getModifiedAfter()).isEqualTo("2025-01-01");
        assertThat(filter.getMimeType()).isEqualTo("application/pdf");
    }

    @Test
    void categoryDroppedWhenNoCategoryPropertyConfigured() {
        when(structuredLlmCaller.call(any(), any(), any(), any(), any()))
                .thenReturn(inferred(null, null, "hr"));

        // categoryProperty defaults to blank, so a category alone yields no usable filter.
        assertThat(service.infer("latest HR policy")).isNull();
    }

    @Test
    void categoryMappedWhenCategoryPropertyConfigured() {
        properties.getFilterInference().setCategoryProperty("source_category");
        when(structuredLlmCaller.call(any(), any(), any(), any(), any()))
                .thenReturn(inferred(null, null, "hr"));

        MetadataFilter filter = service.infer("latest HR policy");

        assertThat(filter).isNotNull();
        assertThat(filter.getProperties()).containsEntry("source_category", "hr");
    }

    @Test
    void nullFromLlm_returnsNull() {
        when(structuredLlmCaller.call(any(), any(), any(), any(), any())).thenReturn(null);

        assertThat(service.infer("some question")).isNull();
    }
}
