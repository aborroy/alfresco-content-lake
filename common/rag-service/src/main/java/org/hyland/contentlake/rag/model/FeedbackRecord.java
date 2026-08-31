package org.hyland.contentlake.rag.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A persisted feedback entry (#74), returned by the feedback listing endpoint and consumed by the
 * offline evaluation harness ({@code cleval feedback import}) to stage regression candidates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackRecord {

    /** Stable id of this feedback document. */
    private String feedbackId;

    private String sessionId;
    private String requestId;
    private FeedbackRating rating;
    private String comment;
    private String question;
    private String answer;
    private List<String> sourceNodeIds;

    /** ISO-8601 creation timestamp. */
    private String createdAt;
}
