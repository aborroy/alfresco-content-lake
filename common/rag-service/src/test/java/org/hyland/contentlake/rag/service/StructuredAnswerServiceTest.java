package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.hyland.contentlake.rag.model.StructuredAnswer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StructuredAnswerServiceTest {

    @Mock
    private StructuredLlmCaller structuredLlmCaller;

    @Test
    void summarize_delegatesToStructuredLlmCaller_andPassesSourcesInUserMessage() {
        StructuredAnswerService service = new StructuredAnswerService(structuredLlmCaller);
        StructuredAnswer expected = new StructuredAnswer(
                "A synopsis.", List.of("point one"), List.of());
        when(structuredLlmCaller.call(any(), any(), eq(StructuredAnswer.class), any(), eq("structured-answer")))
                .thenReturn(expected);

        SearchHit hit = SearchHit.builder().chunkText("Revenue was 4.2M in Q1.").score(0.9).build();
        StructuredAnswer result = service.summarize("What was Q1 revenue?", "Q1 revenue was 4.2M.", List.of(hit));

        assertThat(result).isEqualTo(expected);

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(structuredLlmCaller).call(any(), userCaptor.capture(), eq(StructuredAnswer.class), any(),
                eq("structured-answer"));
        assertThat(userCaptor.getValue())
                .contains("What was Q1 revenue?")
                .contains("Q1 revenue was 4.2M.")
                .contains("Revenue was 4.2M in Q1.");
    }

    @Test
    void summarize_passesFallbackCarryingTheFreeTextAnswer() {
        StructuredAnswerService service = new StructuredAnswerService(structuredLlmCaller);
        ArgumentCaptor<StructuredAnswer> fallbackCaptor = ArgumentCaptor.forClass(StructuredAnswer.class);
        when(structuredLlmCaller.call(any(), any(), eq(StructuredAnswer.class), fallbackCaptor.capture(),
                eq("structured-answer"))).thenAnswer(inv -> fallbackCaptor.getValue());

        StructuredAnswer result = service.summarize("q", "the free text answer", List.of());

        assertThat(result.summary()).isEqualTo("the free text answer");
        assertThat(result.keyPoints()).isEmpty();
        assertThat(result.citations()).isEmpty();
    }
}
