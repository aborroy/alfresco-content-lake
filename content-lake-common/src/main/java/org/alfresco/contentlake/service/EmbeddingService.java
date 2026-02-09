package org.alfresco.contentlake.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.contentlake.model.Chunk;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.retry.TransientAiException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedding service with intelligent fallback handling for oversized inputs.
 *
 * <p>IMPORTANT: This service expects properly chunked input from the chunking layer.
 * If chunks are still too large for the embedding model, it will:
 * <ol>
 *   <li>Split the text at semantic boundaries</li>
 *   <li>Embed each half separately</li>
 *   <li>Average the vectors to create a single embedding</li>
 * </ol>
 *
 * <p>To avoid this fallback entirely, ensure your chunking strategy uses
 * appropriate maxChunkSize values (typically 800-1200 chars for most models).
 */
@Slf4j
public class EmbeddingService {

    private static final Pattern TOO_LARGE = Pattern.compile("input \\((\\d+) tokens\\) is too large");

    // Safety cap for pathological inputs (e.g., malformed text, binary garbage)
    // This should rarely trigger if chunking is working correctly
    private static final int SAFETY_CAP = 3000;

    private static final int MIN_CHARS = 200;

    private final EmbeddingModel embeddingModel;

    @Getter
    private final String modelName;

    public EmbeddingService(EmbeddingModel embeddingModel, String modelName) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
    }

    public List<Double> embed(String text) {
        return embedWithFallback(sanitize(text));
    }

    public List<ChunkWithEmbedding> embedChunks(List<Chunk> chunks) {
        List<ChunkWithEmbedding> results = new ArrayList<>();
        for (Chunk chunk : chunks) {
            String text = chunk.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            results.add(new ChunkWithEmbedding(chunk, embed(text)));
        }
        return results;
    }

    private List<Double> embedWithFallback(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Safety cap for pathological inputs
        if (text.length() > SAFETY_CAP) {
            log.warn("Embedding input exceeds SAFETY_CAP ({} > {}). " +
                            "This indicates a chunking issue. Truncating and logging for investigation.",
                    text.length(), SAFETY_CAP);
            text = text.substring(0, SAFETY_CAP);
        }

        try {
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(List.of(text), null));
            float[] embedding = response.getResults().get(0).getOutput();
            return toDoubleList(embedding);

        } catch (TransientAiException ex) {
            if (!looksLikeTooLarge(ex)) {
                throw ex;
            }

            // If already small, try aggressive trimming
            if (text.length() <= MIN_CHARS) {
                String trimmed = trimWorstParts(text);
                if (trimmed.length() == text.length()) {
                    // Last resort: cut to half
                    int newLen = Math.max(1, text.length() / 2);
                    log.warn("Embedding request still too large at {} chars; " +
                            "last resort truncation to {} chars.", text.length(), newLen);
                    trimmed = text.substring(0, newLen);
                } else {
                    log.warn("Embedding request too large at {} chars; " +
                            "trimmed to {} chars using trimWorstParts().", text.length(), trimmed.length());
                }

                EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(List.of(trimmed), null));
                float[] embedding = response.getResults().get(0).getOutput();
                return toDoubleList(embedding);
            }

            // Split and average: preserve both halves by embedding each and averaging vectors
            int mid = findSplitPoint(text);
            String left = text.substring(0, mid);
            String right = text.substring(mid);

            log.info("Embedding request too large ({} chars). " +
                            "Splitting into two parts (left={}, right={}) and averaging vectors. " +
                            "Consider reducing maxChunkSize in chunking config.",
                    text.length(), left.length(), right.length());

            List<Double> leftVec = embedWithFallback(left);
            List<Double> rightVec = embedWithFallback(right);

            // If one side failed into empty, return the other
            if (leftVec.isEmpty()) {
                return rightVec;
            }
            if (rightVec.isEmpty()) {
                return leftVec;
            }

            if (leftVec.size() != rightVec.size()) {
                throw new IllegalStateException(
                        "Embedding dimension mismatch after split: " +
                                "left=" + leftVec.size() + ", right=" + rightVec.size());
            }

            // Average element-wise
            int dim = leftVec.size();
            List<Double> avg = new ArrayList<>(dim);
            for (int i = 0; i < dim; i++) {
                avg.add((leftVec.get(i) + rightVec.get(i)) / 2.0d);
            }
            return avg;
        }
    }

    private boolean looksLikeTooLarge(TransientAiException ex) {
        String msg = ex.getMessage();
        if (msg == null) return false;
        return TOO_LARGE.matcher(msg).find() || msg.contains("physical batch size");
    }

    private int findSplitPoint(String text) {
        int mid = text.length() / 2;

        // Prefer splitting on paragraph / sentence / whitespace near the midpoint
        int best;

        best = lastIndexBefore(text, '\n', mid, 120);
        if (best > 0) return best;

        best = lastIndexBefore(text, '.', mid, 120);
        if (best > 0) return best + 1;

        best = lastIndexBefore(text, ' ', mid, 120);
        if (best > 0) return best;

        return mid;
    }

    private int lastIndexBefore(String text, char ch, int from, int window) {
        int start = Math.max(0, from - window);
        for (int i = from; i >= start; i--) {
            if (text.charAt(i) == ch) return i;
        }
        return -1;
    }

    private String sanitize(String text) {
        // Remove nulls, collapse pathological whitespace, keep content
        String s = text.replace("\u0000", "");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private String trimWorstParts(String text) {
        // Heuristic: drop very long "words" (often PDF garbage / encoded runs)
        String[] parts = text.split(" ");
        StringBuilder sb = new StringBuilder(text.length());
        for (String p : parts) {
            if (p.length() > 80) continue;
            sb.append(p).append(' ');
        }
        return sb.toString().trim();
    }

    private List<Double> toDoubleList(float[] array) {
        List<Double> list = new ArrayList<>(array.length);
        for (float f : array) list.add((double) f);
        return list;
    }

    public record ChunkWithEmbedding(Chunk chunk, List<Double> embedding) {}
}