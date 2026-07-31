package org.hyland.contentlake.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-reranker fallback used when no dedicated TEI cross-encoder is configured.
 *
 * <p>Scores each candidate chunk's relevance to the query on a 1-5 scale in a single batched
 * LLM call, then sorts by that rating. The 1-5 rating is used only for ordering; the original
 * cosine {@code score} on each hit is preserved so downstream displays stay consistent.</p>
 *
 * <p>Registered by {@link org.hyland.contentlake.rag.config.RerankServiceConfig} when no TEI
 * {@code rag.reranker.url} is set and {@code rag.reranker.enabled} is true. Any failure or
 * unparseable response degrades gracefully to the input order (top-N kept).</p>
 */
@Slf4j
public class LlmRerankService implements RerankService {

    /** Upper bound on chunk characters included in the prompt, to keep token usage bounded. */
    private static final int MAX_CHUNK_CHARS = 500;

    /** Matches lines like "3=5", "3: 5", "[3] 4" emitted by the model. */
    private static final Pattern SCORE_LINE = Pattern.compile("\\[?(\\d+)\\]?\\s*[=:]?\\s*(\\d+)");

    private final ChatModel chatModel;
    private final RagProperties ragProperties;

    public LlmRerankService(ChatModel chatModel, RagProperties ragProperties) {
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
    }

    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        int topN = ragProperties.getReranker().getTopN();

        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage("""
                    You score how relevant each document passage is to a user query.
                    Rate each passage from 1 (irrelevant) to 5 (highly relevant).
                    Return ONLY one line per passage in the form INDEX=SCORE, e.g.:
                    0=5
                    1=2
                    Do not add any other text."""));
            messages.add(new UserMessage(buildPrompt(query, hits)));

            ChatResponse response = chatModel.call(new Prompt(messages));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                log.warn("LLM reranking returned no output; keeping input order");
                return keepTop(hits, topN);
            }

            String text = response.getResult().getOutput().getText();
            Map<Integer, Integer> scoreByIndex = parseScores(text, hits.size());
            if (scoreByIndex.isEmpty()) {
                log.warn("LLM reranking response unparseable; keeping input order");
                return keepTop(hits, topN);
            }

            // Stable sort by LLM rating desc; missing ratings sort last while preserving input order.
            List<SearchHit> sorted = new ArrayList<>(hits);
            sorted.sort(Comparator.comparingInt(
                    (SearchHit h) -> scoreByIndex.getOrDefault(hits.indexOf(h), 0)).reversed());

            List<SearchHit> top = keepTop(sorted, topN);
            List<SearchHit> reranked = new ArrayList<>(top.size());
            int rank = 1;
            for (SearchHit original : top) {
                reranked.add(SearchHit.builder()
                        .rank(rank++)
                        .score(original.getScore())   // preserve original cosine score
                        .chunkText(original.getChunkText())
                        .sourceDocument(original.getSourceDocument())
                        .chunkMetadata(original.getChunkMetadata())
                        .vector(original.getVector())
                        .build());
            }

            log.info("LLM reranker: {} candidates -> {} results (topN={})", hits.size(), reranked.size(), topN);
            return reranked;

        } catch (Exception e) {
            log.warn("LLM reranking failed; falling back to input order: {}", e.getMessage());
            return keepTop(hits, topN);
        }
    }

    private static String buildPrompt(String query, List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("Query:\n").append(query != null ? query.trim() : "").append("\n\nPassages:\n");
        for (int i = 0; i < hits.size(); i++) {
            String chunk = hits.get(i).getChunkText();
            if (chunk == null) {
                chunk = "";
            }
            if (chunk.length() > MAX_CHUNK_CHARS) {
                chunk = chunk.substring(0, MAX_CHUNK_CHARS);
            }
            sb.append('[').append(i).append("] ").append(chunk.replace('\n', ' ').trim()).append('\n');
        }
        sb.append("\nScores (INDEX=SCORE):\n");
        return sb.toString();
    }

    private static Map<Integer, Integer> parseScores(String text, int candidateCount) {
        Map<Integer, Integer> scores = new HashMap<>();
        if (text == null || text.isBlank()) {
            return scores;
        }
        Matcher matcher = SCORE_LINE.matcher(text);
        while (matcher.find()) {
            try {
                int index = Integer.parseInt(matcher.group(1));
                int score = Integer.parseInt(matcher.group(2));
                if (index >= 0 && index < candidateCount) {
                    scores.putIfAbsent(index, score);
                }
            } catch (NumberFormatException ignored) {
                // skip malformed match
            }
        }
        return scores;
    }

    private static List<SearchHit> keepTop(List<SearchHit> hits, int topN) {
        return hits.size() <= topN ? List.copyOf(hits) : List.copyOf(hits.subList(0, topN));
    }
}
