package org.hyland.contentlake.rag.controller;

import org.hyland.contentlake.rag.model.FeedbackRating;
import org.hyland.contentlake.rag.service.FeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which listing the request reaches. The aggregate view is one query parameter away from the scoped
 * one, so these assert that reaching it takes asking for it explicitly.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackControllerTest {

    @Mock FeedbackService feedbackService;

    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        controller = new FeedbackController(feedbackService);
    }

    @Test
    void list_defaultsToTheCallersOwnFeedback() {
        when(feedbackService.list(FeedbackRating.DOWN, 200)).thenReturn(List.of());

        controller.list("down", 200, "own");

        verify(feedbackService, never()).listAll(any(), anyInt());
    }

    @Test
    void list_scopeAll_asksForTheOperatorView() {
        when(feedbackService.listAll(FeedbackRating.DOWN, 200)).thenReturn(List.of());

        controller.list("down", 200, "all");

        verify(feedbackService, never()).list(any(), anyInt());
    }

    @Test
    void list_unknownScope_readsAsTheCallersOwnFeedback() {
        // A mistyped scope narrows the result rather than widening it.
        when(feedbackService.list(null, 200)).thenReturn(List.of());

        controller.list(null, 200, "everything");

        verify(feedbackService, never()).listAll(any(), anyInt());
    }
}
