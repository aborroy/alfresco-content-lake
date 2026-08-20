package org.hyland.contentlake.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.service.EmbeddingService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns one user query into the set of query formulations a search should actually run.
 *
 * <p>Three independent expansions feed the same list, each behind its own flag:</p>
 * <ul>
 *   <li><strong>Decomposition</strong> ({@code rag.query-decomposition.enabled}) - splits a compound
 *       question into independently-retrievable sub-questions, so a two-part question cannot have one
 *       part crowded out of the context by the other.</li>
 *   <li><strong>HyDE</strong> ({@code rag.hyde.enabled}) - drafts an answer-shaped passage and embeds
 *       it document-side. A hypothesis vector sits closer to real answer chunks than a question vector
 *       does, which bridges the register gap when a question is worded nothing like its source.</li>
 *   <li><strong>Multi-query</strong> ({@code rag.multi-query.enabled}) - alternative phrasings of the
 *       same question.</li>
 * </ul>
 *
 * <p>Expansions are appended in that order because
 * {@code rag.query-expansion.max-variants} truncates from the tail: a dropped sub-question loses a
 * whole facet of the question, a dropped paraphrase loses very little.</p>
 *
 * <p>Returns {@code null} when no expansion is enabled, which callers treat as "run the single pass
 * you would have run anyway". That keeps the disabled path free of any allocation or branching cost
 * and makes the disabled behaviour byte-identical to a build without this service.</p>
 *
 * <p>Injects {@link ChatModel} rather than the {@code ragChatClient}, deliberately: the chat client
 * carries {@link ContentLakeRetrievalAdvisor} as a default advisor, so calling it here would re-enter
 * retrieval from inside retrieval.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryExpansionService {

    /** Strips list decoration ("1.", "- ", "* ", "1)", bullet) from a model-emitted line. */
    private static final Pattern LIST_DECORATION = Pattern.compile("^\\s*(?:[-*\\u2022]|\\d+[.)])\\s*");

    /** Emitted by the decomposition prompt when a question has only one facet. */
    private static final String NOT_COMPOUND = "NONE";

    private final ChatModel chatModel;
    private final EmbeddingService embeddingService;
    private final RagProperties ragProperties;

    /**
     * Expands a query into the variants to retrieve for.
     *
     * @return the variants to run, the first of which is always the original query, or {@code null}
     *         when no expansion is enabled and the caller should run its normal single pass
     */
    public List<QueryVariant> expand(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        boolean decompose = ragProperties.getQueryDecomposition().isEnabled();
        boolean hyde = ragProperties.getHyde().isEnabled();
        boolean multiQuery = ragProperties.getMultiQuery().isEnabled();
        if (!decompose && !hyde && !multiQuery) {
            return null;
        }

        String original = query.trim();
        List<QueryVariant> variants = new ArrayList<>();
        variants.add(QueryVariant.original(original));
        Set<String> seen = new LinkedHashSet<>();
        seen.add(original.toLowerCase());

        if (decompose) {
            appendSubQuestions(original, variants, seen);
        }
        if (hyde) {
            appendHypothetical(original, variants);
        }
        if (multiQuery) {
            appendParaphrases(original, variants, seen);
        }

        List<QueryVariant> capped = cap(variants);
        if (capped.size() > 1) {
            log.info("Query expansion: {} variants ({})", capped.size(),
                    capped.stream().map(QueryVariant::label).toList());
        }
        return capped;
    }

    // ---------------------------------------------------------------
    // Decomposition
    // ---------------------------------------------------------------

    private void appendSubQuestions(String query, List<QueryVariant> variants, Set<String> seen) {
        int max = Math.max(1, ragProperties.getQueryDecomposition().getMaxSubQuestions());

        List<String> subQuestions = askForLines(
                """
                You split compound questions into independently answerable sub-questions.
                Rules:
                1. A compound question asks for two or more separate facts, or asks to compare or
                   relate two things.
                2. If the question asks for only one thing, reply with exactly: %s
                3. Otherwise return one sub-question per line, at most %d lines.
                4. Each sub-question must stand alone: resolve every pronoun and reference against the
                   original question.
                5. Do not invent facts and do not add sub-questions the original does not ask for.
                6. Return ONLY the sub-questions, one per line, with no numbering or commentary."""
                        .formatted(NOT_COMPOUND, max),
                "Question:\n%s\n\nSub-questions:\n".formatted(query),
                "query decomposition");

        if (subQuestions.isEmpty() || NOT_COMPOUND.equalsIgnoreCase(subQuestions.get(0).trim())) {
            return;
        }

        int added = 0;
        for (String subQuestion : subQuestions) {
            if (added >= max) {
                break;
            }
            if (NOT_COMPOUND.equalsIgnoreCase(subQuestion.trim())) {
                continue;
            }
            if (addIfNew(variants, seen, "sub-" + (added + 1), subQuestion)) {
                added++;
            }
        }
    }

    // ---------------------------------------------------------------
    // HyDE
    // ---------------------------------------------------------------

    private void appendHypothetical(String query, List<QueryVariant> variants) {
        String passage = askForText(
                """
                You draft the passage that would answer a question, as it would appear in an internal
                document.
                Rules:
                1. Write in the register of the source material: declarative prose, no question form,
                   no hedging, no "the document says".
                2. Two to four sentences.
                3. Plausible specifics are fine. This passage is never shown to anyone; it is used only
                   as a search vector, so being wrong costs nothing and being vague costs recall.
                4. Return ONLY the passage.""",
                "Question:\n%s\n\nPassage:\n".formatted(query),
                "HyDE");

        if (passage == null || passage.isBlank()) {
            return;
        }

        int maxChars = Math.max(1, ragProperties.getHyde().getMaxChars());
        String truncated = passage.length() > maxChars ? passage.substring(0, maxChars) : passage;

        // Document-side embedding: no query instruction prefix. The whole premise of HyDE is that the
        // text is answer-shaped, so it must be embedded the way the stored chunks were.
        List<Double> vector;
        try {
            vector = embeddingService.embed(truncated);
        } catch (Exception e) {
            log.warn("HyDE embedding failed, skipping the hypothetical variant: {}", e.getMessage());
            return;
        }
        if (vector == null || vector.isEmpty()) {
            log.warn("HyDE embedding was empty, skipping the hypothetical variant");
            return;
        }

        variants.add(QueryVariant.vectorOnly("hyde", truncated, vector));
    }

    // ---------------------------------------------------------------
    // Multi-query
    // ---------------------------------------------------------------

    private void appendParaphrases(String query, List<QueryVariant> variants, Set<String> seen) {
        int requested = Math.max(1, ragProperties.getMultiQuery().getVariants());

        List<String> paraphrases = askForLines(
                """
                You rewrite a search query into alternative formulations that retrieve the same
                information from different angles.
                Produce at most %d lines, each a complete standalone query:
                1. A close paraphrase using different vocabulary for the same concepts.
                2. A keyword-style formulation: the exact terms a document would use, no question words.
                3. A formulation that expands any acronym or abbreviation, or contracts any expanded
                   term into its acronym.
                Rules: preserve the information need exactly, do not narrow or broaden it, do not invent
                constraints, and return ONLY the queries, one per line, with no numbering or commentary."""
                        .formatted(requested),
                "Query:\n%s\n\nAlternative queries:\n".formatted(query),
                "multi-query expansion");

        int added = 0;
        for (String paraphrase : paraphrases) {
            if (added >= requested) {
                break;
            }
            if (addIfNew(variants, seen, "variant-" + (added + 1), paraphrase)) {
                added++;
            }
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private boolean addIfNew(List<QueryVariant> variants, Set<String> seen, String label, String text) {
        if (text == null) {
            return false;
        }
        String cleaned = LIST_DECORATION.matcher(text).replaceFirst("").trim();
        if (cleaned.isBlank() || !seen.add(cleaned.toLowerCase())) {
            return false;
        }
        variants.add(QueryVariant.rephrased(label, cleaned));
        return true;
    }

    /**
     * Truncates from the tail to {@code rag.query-expansion.max-variants}, always keeping the original.
     */
    private List<QueryVariant> cap(List<QueryVariant> variants) {
        int max = Math.max(1, ragProperties.getQueryExpansion().getMaxVariants());
        if (variants.size() <= max) {
            return List.copyOf(variants);
        }
        log.debug("Query expansion capped at {} variants, dropping {}", max, variants.size() - max);
        return List.copyOf(variants.subList(0, max));
    }

    /** One LLM call, newline-split. Returns an empty list on any failure. */
    private List<String> askForLines(String system, String user, String what) {
        String text = askForText(system, user, what);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    /**
     * One LLM call. Returns {@code null} on any failure, so every expansion degrades to "this variant
     * is simply not added" rather than failing the search.
     */
    private String askForText(String system, String user, String what) {
        List<Message> messages = List.of(new SystemMessage(system), new UserMessage(user));
        try {
            ChatResponse response = chatModel.call(new Prompt(messages));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                log.warn("{} returned no output; continuing without it", what);
                return null;
            }
            String text = response.getResult().getOutput().getText();
            return text != null ? text.trim() : null;
        } catch (Exception e) {
            log.warn("{} failed, continuing without it: {}", what, e.getMessage());
            return null;
        }
    }
}
