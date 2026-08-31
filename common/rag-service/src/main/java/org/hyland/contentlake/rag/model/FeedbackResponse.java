package org.hyland.contentlake.rag.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Acknowledgement for a stored feedback entry (#74). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackResponse {

    /** True when the feedback was persisted. */
    private boolean stored;

    /** Stable id of the stored feedback document (null when not stored). */
    private String feedbackId;
}
