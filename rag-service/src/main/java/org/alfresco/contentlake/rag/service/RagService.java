package org.alfresco.contentlake.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.contentlake.rag.config.RagProperties;
import org.alfresco.contentlake.rag.model.RagPromptRequest;
import org.alfresco.contentlake.rag.model.RagPromptResponse;
import org.alfresco.contentlake.rag.model.RagPromptResponse.ContextChunk;
import org.alfresco.contentlake.rag.model.RagPromptResponse.Source;
import org.alfresco.contentlake.rag.model.SemanticSearchRequest;
import org.alfresco.contentlake.rag.model.SemanticSearchResponse;
import org.alfresco.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG (Retrieval-Augmented Generation) service.
 *
 * <p>Orchestrates the three-phase pipeline:
 * <ol>
 *   <li><strong>Retrieve</strong> — Permission-filtered semantic search via {@link SemanticSearchService}</li>
 *   <li><strong>Augment</strong> — Assembles a grounded prompt with retrieved chunks as context</li>
 *   <li><strong>Generate</strong> — Calls the LLM via Spring AI {@link ChatModel}</li>
 * </ol>
 *
 * <p>The context sent to the LLM is capped at {@code rag.max-context-length} characters
 * to stay within reasonable token limits for the model.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final SemanticSearchService semanticSearchService;
    private final ChatModel chatModel;
    private final RagProperties ragProperties;

    /**
     * Executes the full RAG pipeline for a given question.
     *
     * @param request the RAG prompt request
     * @return response with generated answer, sources, and timing
     */
    public RagPromptResponse prompt(RagPromptRequest request) {
        long totalStart = System.currentTimeMillis();

        // --- 1. RETRIEVE ---
        int topK = request.getTopK() > 0 ? request.getTopK() : ragProperties.getDefaultTopK();
        double minScore = request.getMinScore() > 0 ? request.getMinScore() : ragProperties.getDefaultMinScore();

        SemanticSearchRequest searchRequest = SemanticSearchRequest.builder()
                .query(request.getQuestion())
                .topK(topK)
                .minScore(minScore)
                .filter(request.getFilter())
                .embeddingType(request.getEmbeddingType())
                .build();

        log.info("RAG retrieve phase: query=\"{}\", topK={}, minScore={}", request.getQuestion(), topK, minScore);
        SemanticSearchResponse searchResponse = semanticSearchService.search(searchRequest);
        long searchTimeMs = searchResponse.getSearchTimeMs();

        List<SearchHit> hits = searchResponse.getResults() != null ? searchResponse.getResults() : List.of();
        log.info("RAG retrieve phase complete: {} chunks retrieved in {}ms", hits.size(), searchTimeMs);

        // --- 2. AUGMENT ---
        String contextBlock = assembleContext(hits);
        String systemPrompt = resolveSystemPrompt(request);
        String userPrompt = buildUserPrompt(request.getQuestion(), contextBlock);

        log.debug("RAG augment phase: context length={} chars, {} sources", contextBlock.length(), hits.size());

        // --- 3. GENERATE ---
        long genStart = System.currentTimeMillis();
        String answer;
        String modelName;

        if (hits.isEmpty()) {
            answer = "I couldn't find any relevant documents to answer your question. "
                    + "Please try rephrasing your query or ensure the relevant documents have been ingested.";
            modelName = "none (no context available)";
        } else {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.add(new UserMessage(userPrompt));

            try {
                ChatResponse chatResponse = chatModel.call(new Prompt(messages));
                answer = chatResponse.getResult().getOutput().getText();
                modelName = chatResponse.getMetadata() != null && chatResponse.getMetadata().getModel() != null
                        ? chatResponse.getMetadata().getModel()
                        : "unknown";
                log.info("RAG generate phase complete: model={}, answer length={} chars",
                        modelName, answer.length());
            } catch (Exception e) {
                log.error("LLM generation failed: {}", e.getMessage(), e);
                answer = "An error occurred while generating the answer: " + e.getMessage();
                modelName = "error";
            }
        }

        long generationTimeMs = System.currentTimeMillis() - genStart;
        long totalTimeMs = System.currentTimeMillis() - totalStart;

        // --- BUILD RESPONSE ---
        List<Source> sources = hits.stream()
                .map(hit -> Source.builder()
                        .documentId(hit.getSourceDocument() != null ? hit.getSourceDocument().getDocumentId() : null)
                        .nodeId(hit.getSourceDocument() != null ? hit.getSourceDocument().getNodeId() : null)
                        .name(hit.getSourceDocument() != null ? hit.getSourceDocument().getName() : null)
                        .path(hit.getSourceDocument() != null ? hit.getSourceDocument().getPath() : null)
                        .chunkText(hit.getChunkText())
                        .score(hit.getScore())
                        .build())
                .toList();

        List<ContextChunk> contextChunks = null;
        if (request.isIncludeContext()) {
            contextChunks = hits.stream()
                    .map(hit -> ContextChunk.builder()
                            .rank(hit.getRank())
                            .score(hit.getScore())
                            .text(hit.getChunkText())
                            .sourceName(hit.getSourceDocument() != null ? hit.getSourceDocument().getName() : null)
                            .sourcePath(hit.getSourceDocument() != null ? hit.getSourceDocument().getPath() : null)
                            .build())
                    .toList();
        }

        return RagPromptResponse.builder()
                .answer(answer)
                .question(request.getQuestion())
                .model(modelName)
                .searchTimeMs(searchTimeMs)
                .generationTimeMs(generationTimeMs)
                .totalTimeMs(totalTimeMs)
                .sourcesUsed(sources.size())
                .sources(sources)
                .context(contextChunks)
                .build();
    }

    // ---------------------------------------------------------------
    // Context assembly
    // ---------------------------------------------------------------

    /**
     * Assembles chunk texts into a single context string, respecting the max length.
     * Each chunk is wrapped with source attribution for the LLM.
     */
    private String assembleContext(List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return "";
        }

        int maxLength = ragProperties.getMaxContextLength();
        StringBuilder context = new StringBuilder();
        int chunkIndex = 1;

        for (SearchHit hit : hits) {
            String sourceName = hit.getSourceDocument() != null && hit.getSourceDocument().getName() != null
                    ? hit.getSourceDocument().getName()
                    : "Unknown document";

            String chunkEntry = String.format(
                    "[Source %d: %s (score: %.2f)]\n%s\n\n",
                    chunkIndex++, sourceName, hit.getScore(), hit.getChunkText()
            );

            if (context.length() + chunkEntry.length() > maxLength) {
                int remaining = maxLength - context.length();
                if (remaining > 100) {
                    context.append(chunkEntry, 0, remaining);
                    context.append("\n... (context truncated)");
                }
                break;
            }

            context.append(chunkEntry);
        }

        return context.toString().trim();
    }

    // ---------------------------------------------------------------
    // Prompt building
    // ---------------------------------------------------------------

    private String resolveSystemPrompt(RagPromptRequest request) {
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            return request.getSystemPrompt();
        }
        return ragProperties.getDefaultSystemPrompt();
    }

    private String buildUserPrompt(String question, String context) {
        return String.format("""
                Based on the following document context, answer the question.

                --- DOCUMENT CONTEXT ---
                %s
                --- END CONTEXT ---

                Question: %s

                Answer:""",
                context, question);
    }
}
