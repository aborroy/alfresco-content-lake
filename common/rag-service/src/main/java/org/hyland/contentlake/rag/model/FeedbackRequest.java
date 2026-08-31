package org.hyland.contentlake.rag.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Payload for {@code POST /api/rag/feedback} (#74): a user's rating of a generated answer, plus the
 * context needed to turn a negative rating into an evaluation regression case.
 *
 * <p>{@code requestId} correlates the feedback with the exact answer (echoed from
 * {@link RagPromptResponse#getRequestId()}). The question/answer/source fields are echoed by the
 * caller from the response it received so the feedback record is self-contained for the offline
 * corpus, without the server having to retain every answer.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackRequest {

    /** Conversation session id the answer belonged to (optional). */
    private String sessionId;

    /** Correlation id of the rated answer (from {@link RagPromptResponse#getRequestId()}). */
    private String requestId;

    /** Up (useful/correct) or down (wrong/unhelpful). Required. */
    private FeedbackRating rating;

    /** Optional free-text comment explaining the rating. */
    private String comment;

    /** The original question, echoed for corpus building (optional but recommended). */
    private String question;

    /** The answer that was rated, echoed for corpus building (optional). */
    private String answer;

    /** Node ids of the sources the answer cited, echoed for corpus building (optional). */
    private List<String> sourceNodeIds;
}
