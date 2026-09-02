package org.hyland.contentlake.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.conversation.SessionSummaryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the persistent running conversation summary (#50) for a session, so a client can show a
 * conversation-memory panel without issuing a prompt. The same value is also returned inline on the
 * prompt/chat response as {@code currentSummary}.
 *
 * <p>Requires authentication like the other {@code /api/rag/**} endpoints.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/sessions")
@RequiredArgsConstructor
public class SessionSummaryController {

    private final SessionSummaryService sessionSummaryService;

    /**
     * Returns the current stored summary for a session.
     *
     * @param sessionId the conversation session id
     * @return {@code 200} with {@link SummaryResponse} (summary may be null when none exists yet);
     *         {@code 404} when the summary feature is disabled
     */
    @GetMapping("/{sessionId}/summary")
    public ResponseEntity<SummaryResponse> summary(@PathVariable String sessionId) {
        if (!sessionSummaryService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        String summary = sessionSummaryService.loadSummary(sessionId);
        return ResponseEntity.ok(new SummaryResponse(sessionId, summary));
    }

    /** Session summary payload; {@code summary} is null when none has been generated yet. */
    public record SummaryResponse(String sessionId, String summary) {
    }
}
