package org.hyland.contentlake.rag.service;

import java.util.List;

/**
 * One query formulation to run a retrieval pass for.
 *
 * <p>A search may run several of these and fuse the result sets. The vector and keyword sides carry
 * separate text because the two legs want different things from an expansion:</p>
 * <ul>
 *   <li>A multi-query paraphrase is a question, so it drives both legs.</li>
 *   <li>A HyDE passage is answer-shaped prose. It belongs on the vector leg, where proximity to real
 *       answer chunks is the point, and not on the keyword leg, where its incidental vocabulary would
 *       flood the BM25 term list and displace the terms the user actually asked about.</li>
 * </ul>
 *
 * @param label        short identifier for logging and diagnostics (e.g. {@code original}, {@code hyde})
 * @param vectorText   text embedded for the vector leg
 * @param vectorVector pre-computed embedding for {@code vectorText}, or {@code null} to embed it
 *                     query-side at search time. HyDE supplies one because its passage must be
 *                     embedded document-side, without the query instruction prefix.
 * @param keywordText  text used for keyword/fulltext matching, or {@code null} to skip the keyword leg
 */
public record QueryVariant(String label,
                           String vectorText,
                           List<Double> vectorVector,
                           String keywordText) {

    /** The user's query, unmodified, embedded query-side and driving both legs. */
    public static final String LABEL_ORIGINAL = "original";

    /** The variant every search runs, with or without expansion enabled. */
    public static QueryVariant original(String query) {
        return new QueryVariant(LABEL_ORIGINAL, query, null, query);
    }

    /** An alternative phrasing of the question; drives both legs, embedded query-side. */
    public static QueryVariant rephrased(String label, String text) {
        return new QueryVariant(label, text, null, text);
    }

    /** A vector-only variant carrying its own document-side embedding. */
    public static QueryVariant vectorOnly(String label, String text, List<Double> vector) {
        return new QueryVariant(label, text, vector, null);
    }

    /** True when this variant should contribute to the keyword leg. */
    public boolean hasKeywordLeg() {
        return keywordText != null && !keywordText.isBlank();
    }
}
