package org.hyland.contentlake.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.hyland.contentlake.rag.model.StructuredAnswer;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Derives a typed {@link StructuredAnswer} from an already-generated free-text answer and the
 * reranked source hits (#70).
 *
 * <p>Runs as a second pass over the free-text answer rather than replacing generation, so the
 * primary {@code answer} field is byte-for-byte unchanged and the eval baseline is unaffected. The
 * single LLM call routes through {@link StructuredLlmCaller}, which uses Spring AI's
 * {@code BeanOutputConverter} and degrades to the supplied fallback on any failure.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredAnswerService {

    private static final int MAX_SOURCE_SNIPPET = 400;

    /**
     * How many of the reranked hits this pass sees. Feeding it every hit made the prompt scale with
     * {@code rag.default-top-k} -- 30 sources on a demo deployment, ~12k characters -- for a call
     * whose only job is to name the sources the answer drew on. The hits are already ordered by
     * relevance, so the top few carry what a citation can point at.
     */
    private static final int MAX_SOURCES = 8;
    private static final String SYSTEM = """
            You convert a document-grounded answer into a structured JSON object.
            Use ONLY the ANSWER and SOURCES provided; do not add facts.
            - summary: a 1-3 sentence synopsis of the answer.
            - keyPoints: the salient facts as short, standalone points.
            - citations: the sources the answer drew from, each with the source name and a short
              supporting quote taken verbatim from that source.
            If the answer is empty or says no information was found, return an empty summary and empty lists.""";

    private final StructuredLlmCaller structuredLlmCaller;

    /**
     * Produces a {@link StructuredAnswer} for the given free-text answer.
     *
     * @param question the user's question
     * @param answer   the generated free-text answer
     * @param hits     the reranked source hits used to ground the answer
     * @return a structured answer; on any LLM/parse failure, a fallback carrying {@code answer} as the
     *         summary with empty lists
     */
    public StructuredAnswer summarize(String question, String answer, List<SearchHit> hits) {
        String user = "QUESTION:\n" + safe(question)
                + "\n\nANSWER:\n" + safe(answer)
                + "\n\nSOURCES:\n" + formatSources(hits);
        StructuredAnswer fallback = new StructuredAnswer(safe(answer), List.of(), List.of());
        return structuredLlmCaller.call(SYSTEM, user, StructuredAnswer.class, fallback, "structured-answer");
    }

    private static String formatSources(List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (SearchHit hit : hits.subList(0, Math.min(hits.size(), MAX_SOURCES))) {
            String name = hit.getSourceDocument() != null && hit.getSourceDocument().getName() != null
                    ? hit.getSourceDocument().getName()
                    : "Unknown document";
            String text = hit.getChunkText() != null ? hit.getChunkText() : "";
            if (text.length() > MAX_SOURCE_SNIPPET) {
                text = text.substring(0, MAX_SOURCE_SNIPPET);
            }
            sb.append("Source ").append(i++).append(": ").append(name).append('\n')
                    .append(text).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
