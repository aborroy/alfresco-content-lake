package org.hyland.contentlake.rag.controller;

import org.hyland.contentlake.rag.conversation.SessionSummaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionSummaryControllerTest {

    @Mock
    SessionSummaryService sessionSummaryService;

    @InjectMocks
    SessionSummaryController controller;

    @Test
    void summary_featureDisabled_returns404() {
        when(sessionSummaryService.isEnabled()).thenReturn(false);

        ResponseEntity<SessionSummaryController.SummaryResponse> response = controller.summary("user:alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(sessionSummaryService, never()).loadSummary("user:alice");
    }

    @Test
    void summary_enabled_returnsSummary() {
        when(sessionSummaryService.isEnabled()).thenReturn(true);
        when(sessionSummaryService.loadSummary("user:alice")).thenReturn("running summary text");

        ResponseEntity<SessionSummaryController.SummaryResponse> response = controller.summary("user:alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sessionId()).isEqualTo("user:alice");
        assertThat(response.getBody().summary()).isEqualTo("running summary text");
    }

    @Test
    void summary_enabledButNoneStored_returns200WithNullSummary() {
        when(sessionSummaryService.isEnabled()).thenReturn(true);
        when(sessionSummaryService.loadSummary("user:bob")).thenReturn(null);

        ResponseEntity<SessionSummaryController.SummaryResponse> response = controller.summary("user:bob");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().summary()).isNull();
    }
}
