package org.hyland.contentlake.rag.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one token proxy this service uses: a whitespace-delimited word count.
 *
 * <p>Deliberately not a real tokenizer. A tokenizer dependency would be an OpenAI tokenizer, which
 * gives a wrong answer for every local model this stack runs against, and the point here is a payload
 * for diagnosis rather than a billing figure. Wherever an estimate is recorded it is labelled as one,
 * so nobody compares it against a provider-reported count and concludes the prompt grew.</p>
 *
 * <p>Extracted so {@code RagService} and {@code ContentLakeRetrievalAdvisor} share one definition:
 * two estimators would make a prompt estimate and an answer estimate incomparable.</p>
 */
public final class TokenEstimator {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\S+");

    private TokenEstimator() {
    }

    /** Estimated token count, or 0 for null or blank text. */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
