package org.hyland.contentlake.rag.service;

import java.util.List;

/**
 * Cosine similarity over embedding vectors represented as {@code List<Double>}
 * (the form carried by {@code Embedding.getSysembedVector()} and threaded through
 * search hits for MMR diversity selection).
 */
final class CosineSimilarity {

    private CosineSimilarity() {
    }

    /**
     * Computes the cosine similarity between two vectors.
     *
     * @return the cosine similarity in [-1.0, 1.0], or {@code 0.0} when either vector is
     *         null, empty, of mismatched length, or has zero magnitude
     */
    static double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0.0;
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            Double av = a.get(i);
            Double bv = b.get(i);
            if (av == null || bv == null) {
                return 0.0;
            }
            double x = av;
            double y = bv;
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
