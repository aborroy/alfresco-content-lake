package org.hyland.contentlake.rag.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * User verdict on a generated answer (#74). Accepted case-insensitively from JSON
 * ({@code "up"}, {@code "DOWN"}, ...); {@code DOWN} entries are the ones folded into the offline
 * evaluation corpus as regression candidates.
 */
public enum FeedbackRating {
    UP,
    DOWN;

    @JsonCreator
    public static FeedbackRating fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rating is required (up or down)");
        }
        return FeedbackRating.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
