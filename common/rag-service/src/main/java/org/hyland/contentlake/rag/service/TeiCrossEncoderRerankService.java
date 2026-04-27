package org.hyland.contentlake.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Cross-encoder reranker backed by a HuggingFace Text Embeddings Inference (TEI) instance
 * running a cross-encoder model (e.g. cross-encoder/ms-marco-MiniLM-L-6-v2).
 *
 * <p>Registered by {@link org.hyland.contentlake.rag.config.RerankServiceConfig} when
 * {@code rag.reranker.url} is set to a non-blank value.</p>
 *
 * <p>TEI rerank API contract:
 * <pre>
 * POST /rerank
 * { "query": "...", "texts": ["chunk1", "chunk2", ...], "truncate": true }
 *
 * Response: [{ "index": 0, "score": 0.97 }, { "index": 1, "score": 0.42 }, ...]
 * </pre>
 * </p>
 */
@Slf4j
public class TeiCrossEncoderRerankService implements RerankService {

    private final RagProperties ragProperties;
    private final RestTemplate restTemplate;

    public TeiCrossEncoderRerankService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        String url = ragProperties.getReranker().getUrl();
        int topN = ragProperties.getReranker().getTopN();

        List<String> texts = hits.stream()
                .map(h -> h.getChunkText() != null ? h.getChunkText() : "")
                .toList();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("query", query);
        requestBody.put("texts", texts);
        requestBody.put("truncate", true);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url + "/rerank",
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    (Class<List<Map<String, Object>>>) (Class<?>) List.class
            );

            List<Map<String, Object>> scores = response.getBody();
            if (scores == null || scores.isEmpty()) {
                log.warn("Reranker returned empty response; keeping original order");
                return keepTop(hits, topN);
            }

            // Build index→score map from TEI response
            Map<Integer, Double> scoreByIndex = new HashMap<>();
            for (Map<String, Object> entry : scores) {
                Object idx = entry.get("index");
                Object score = entry.get("score");
                if (idx instanceof Number idxNum && score instanceof Number scoreNum) {
                    scoreByIndex.put(idxNum.intValue(), scoreNum.doubleValue());
                }
            }

            // Sort hits by cross-encoder score descending, reassign ranks
            List<SearchHit> sorted = new ArrayList<>(hits);
            sorted.sort(Comparator.comparingDouble(
                    (SearchHit h) -> scoreByIndex.getOrDefault(hits.indexOf(h), 0.0)).reversed());

            List<SearchHit> top = keepTop(sorted, topN);
            List<SearchHit> reranked = new ArrayList<>(top.size());
            for (int i = 0; i < top.size(); i++) {
                SearchHit original = top.get(i);
                double newScore = scoreByIndex.getOrDefault(hits.indexOf(original), original.getScore());
                reranked.add(SearchHit.builder()
                        .rank(i + 1)
                        .score(newScore)
                        .chunkText(original.getChunkText())
                        .sourceDocument(original.getSourceDocument())
                        .chunkMetadata(original.getChunkMetadata())
                        .build());
            }

            log.info("Reranker: {} candidates → {} results (topN={})", hits.size(), reranked.size(), topN);
            return reranked;

        } catch (Exception e) {
            log.warn("Cross-encoder reranking failed ({}); falling back to original order: {}", url, e.getMessage());
            return keepTop(hits, topN);
        }
    }

    private static List<SearchHit> keepTop(List<SearchHit> hits, int topN) {
        return hits.size() <= topN ? List.copyOf(hits) : List.copyOf(hits.subList(0, topN));
    }
}
