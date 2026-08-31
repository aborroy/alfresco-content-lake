package org.hyland.contentlake.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.model.FeedbackRating;
import org.hyland.contentlake.rag.model.FeedbackRecord;
import org.hyland.contentlake.rag.model.FeedbackRequest;
import org.hyland.contentlake.rag.model.FeedbackResponse;
import org.hyland.contentlake.rag.service.FeedbackService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User feedback endpoints (#74). Present only when {@code rag.feedback.enabled=true} (the default).
 *
 * <ul>
 *   <li>{@code POST /api/rag/feedback} - submit a rating (and optional comment) for an answer,
 *       correlated by {@code requestId} from the prompt response.</li>
 *   <li>{@code GET /api/rag/feedback?rating=down} - list stored feedback for the offline evaluation
 *       harness ({@code cleval feedback import}).</li>
 * </ul>
 *
 * <p>Both sit behind the existing authentication chain (HTTP Basic / Alfresco ticket / Nuxeo token),
 * so feedback is never submitted or read anonymously.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.feedback", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping("/feedback")
    public ResponseEntity<FeedbackResponse> submit(@RequestBody FeedbackRequest request) {
        if (request == null || request.getRating() == null) {
            return ResponseEntity.badRequest().body(FeedbackResponse.builder().stored(false).build());
        }
        String feedbackId = feedbackService.store(request);
        return ResponseEntity.ok(FeedbackResponse.builder()
                .stored(feedbackId != null)
                .feedbackId(feedbackId)
                .build());
    }

    @GetMapping("/feedback")
    public ResponseEntity<List<FeedbackRecord>> list(
            @RequestParam(value = "rating", required = false) String rating,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        // Parse the rating param case-insensitively (Spring's default enum binding is case-sensitive).
        FeedbackRating parsed = (rating == null || rating.isBlank()) ? null : FeedbackRating.fromValue(rating);
        return ResponseEntity.ok(feedbackService.list(parsed, limit));
    }
}
