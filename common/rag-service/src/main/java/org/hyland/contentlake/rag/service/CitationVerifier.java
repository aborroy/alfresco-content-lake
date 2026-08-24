package org.hyland.contentlake.rag.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * NLI-style faithfulness verification of a generated answer against its retrieved sources.
 *
 * <p>The system prompt keeps the LLM grounded, but nothing checks the output afterwards, so a
 * hallucinated claim passes through silently. After generation this verifier asks the model which
 * factual claims in the answer are <em>not</em> entailed by the cited context; those are surfaced on
 * the response as {@code unsupportedClaims}, with {@code verified} true only when none remain.</p>
 *
 * <p>Off by default ({@code rag.citation.verify.enabled}); it adds one LLM call per answer. Routed
 * through {@link StructuredLlmCaller}, so a failed or unparseable check fails open (treated as
 * verified with no flagged claims) rather than blocking the response.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CitationVerifier {

    private static final int MAX_SOURCE_CHARS = 2000;

    private static final String SYSTEM_PROMPT = """
            You verify whether an assistant's answer is faithful to the sources it was given.
            You are given the ANSWER and a numbered list of SOURCES (the only evidence available).

            Identify each distinct factual claim in the answer. A claim is supported only if a source
            states or directly entails it. List every claim that is NOT supported by any source,
            quoting or closely paraphrasing it. Ignore generic framing, questions, and hedging.

            Set verified to true only when every factual claim is supported (the unsupported list is
            empty). Do not use outside knowledge: if the sources do not contain it, it is unsupported.""";

    private final StructuredLlmCaller structuredLlmCaller;
    private final RagProperties ragProperties;

    public boolean isEnabled() {
        return ragProperties.getCitation().getVerify().isEnabled();
    }

    /**
     * Verifies the answer against the retrieved hits.
     *
     * @return the verification result, or {@code null} when verification is disabled or there is
     *         nothing to verify (blank answer or no context)
     */
    public VerificationResult verify(String answer, List<SearchHit> hits) {
        if (!isEnabled() || answer == null || answer.isBlank() || hits == null || hits.isEmpty()) {
            return null;
        }

        String user = buildUserPrompt(answer, hits);
        // Fail open: an unverifiable answer is reported as verified with no flagged claims, so the
        // feature never manufactures false alarms when the checker itself is unavailable.
        FaithfulnessVerdict fallback = new FaithfulnessVerdict();
        fallback.setVerified(true);
        fallback.setUnsupportedClaims(List.of());

        FaithfulnessVerdict verdict = structuredLlmCaller.call(
                SYSTEM_PROMPT, user, FaithfulnessVerdict.class, fallback, "citation verification");

        List<String> unsupported = verdict.getUnsupportedClaims() != null
                ? verdict.getUnsupportedClaims()
                : List.of();
        // Trust the list over the boolean: a non-empty unsupported list means not verified, whatever
        // the model set the flag to.
        boolean verified = verdict.isVerified() && unsupported.isEmpty();
        return new VerificationResult(verified, unsupported);
    }

    private String buildUserPrompt(String answer, List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("ANSWER:\n").append(answer.trim()).append("\n\nSOURCES:\n");
        int n = 1;
        for (SearchHit hit : hits) {
            String text = hit.getChunkText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (text.length() > MAX_SOURCE_CHARS) {
                text = text.substring(0, MAX_SOURCE_CHARS);
            }
            sb.append("Source ").append(n++).append(": ").append(text.trim()).append("\n\n");
        }
        return sb.toString();
    }

    /** Verification outcome attached to the RAG response. */
    public record VerificationResult(boolean verified, List<String> unsupportedClaims) {
        public List<String> unsupportedClaims() {
            return unsupportedClaims != null ? unsupportedClaims : new ArrayList<>();
        }
    }

    /** Structured-output shape the LLM fills. */
    @Data
    public static class FaithfulnessVerdict {
        private boolean verified;
        private List<String> unsupportedClaims;
    }
}
