package org.hyland.contentlake.rag.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Built-in structured shape for a RAG answer (#70), returned alongside the free-text {@code answer}
 * when {@link ResponseFormat#STRUCTURED} is requested.
 *
 * @param summary   a short synopsis of the answer
 * @param keyPoints the salient facts as short standalone points
 * @param citations the sources the answer drew from, each with a supporting quote
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StructuredAnswer(String summary, List<String> keyPoints, List<Citation> citations) {

    /**
     * A single citation.
     *
     * @param sourceName the name/label of the cited source document
     * @param quote      a short supporting quote drawn from that source
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(String sourceName, String quote) {}
}
