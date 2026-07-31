package org.hyland.contentlake.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Custom Spring AI {@link org.springframework.ai.chat.client.advisor.api.Advisor} that wraps
 * Content Lake's retrieve -&gt; rerank -&gt; augment pipeline into a single composable unit,
 * replacing the private methods that were inline in {@code RagService}.
 *
 * <p>On {@code before}:</p>
 * <ol>
 *   <li>Builds a {@link Query} from the incoming user message + retrieval params (topK,
 *       minScore, filter, sourceType, embeddingType) carried in the request context.</li>
 *   <li>Retrieves candidate {@link Document}s via {@link HxprDocumentRetriever}.</li>
 *   <li>Reranks them through the {@link RerankService} extension point.</li>
 *   <li>Assembles a bounded context block and rebuilds the user message so the LLM sees
 *       conversation history + grounded document context (same prompt shape as before).</li>
 *   <li>Records the reranked hits + timing into the {@link RetrievalTrace} so the service can
 *       build its response without re-querying hxpr.</li>
 * </ol>
 *
 * <p>When retrieval yields no usable context, the advisor <strong>short-circuits the LLM
 * call</strong> and synthesizes a fallback answer, preserving the previous behavior where
 * the chat model was never invoked for empty context.</p>
 *
 * <p>ACL/permission and source-type filtering are delegated entirely to the search services
 * via the retriever; this advisor adds no filtering of its own.</p>
 */
@Slf4j
public class ContentLakeRetrievalAdvisor implements CallAdvisor, StreamAdvisor {

    /** Advisor param: {@link String} already-reformulated retrieval query. Falls back to the user message text. */
    public static final String PARAM_RETRIEVAL_QUERY = "cl.retrievalQuery";
    /** Advisor param: {@link String} assembled conversation-history block (may be blank). */
    public static final String PARAM_HISTORY_BLOCK = "cl.historyBlock";

    /** Fallback answer emitted (without an LLM call) when no relevant context is retrieved. */
    public static final String NO_CONTEXT_ANSWER =
            "I couldn't find any relevant documents to answer your question. "
                    + "Please try rephrasing your query or ensure the relevant documents have been ingested.";

    private static final int ORDER = 0;

    private final DocumentRetriever documentRetriever;
    private final DiversitySelector diversitySelector;
    private final RerankService rerankService;
    private final RagProperties ragProperties;

    public ContentLakeRetrievalAdvisor(DocumentRetriever documentRetriever,
                                       DiversitySelector diversitySelector,
                                       RerankService rerankService,
                                       RagProperties ragProperties) {
        this.documentRetriever = documentRetriever;
        this.diversitySelector = diversitySelector;
        this.rerankService = rerankService;
        this.ragProperties = ragProperties;
    }

    @Override
    public String getName() {
        return "ContentLakeRetrievalAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // ------------------------------------------------------------------
    // Call (synchronous) path
    // ------------------------------------------------------------------

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Retrieval retrieval = retrieveAndRerank(request);
        if (retrieval.hits().isEmpty()) {
            return fallbackResponse(request);
        }
        return chain.nextCall(augmentRequest(request, retrieval));
    }

    // ------------------------------------------------------------------
    // Stream path
    // ------------------------------------------------------------------

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Retrieval retrieval = retrieveAndRerank(request);
        if (retrieval.hits().isEmpty()) {
            return Flux.just(fallbackResponse(request));
        }
        return chain.nextStream(augmentRequest(request, retrieval));
    }

    // ------------------------------------------------------------------
    // Core pipeline
    // ------------------------------------------------------------------

    private Retrieval retrieveAndRerank(ChatClientRequest request) {
        Map<String, Object> context = request.context();
        String userText = userText(request);
        String retrievalQuery = stringParam(context.get(PARAM_RETRIEVAL_QUERY), userText);

        long searchStart = System.currentTimeMillis();
        Query query = Query.builder()
                .text(retrievalQuery)
                .context(context)
                .build();
        List<Document> documents = documentRetriever.retrieve(query);
        long searchTimeMs = System.currentTimeMillis() - searchStart;

        List<SearchHit> hits = new ArrayList<>();
        for (Document document : documents) {
            SearchHit hit = HxprDocumentRetriever.hitOf(document);
            if (hit != null) {
                hits.add(hit);
            }
        }

        // MMR diversity selection: trim the over-retrieved pool down to topK before reranking,
        // so relevance (rerank) and diversity (MMR) compose instead of competing over final order.
        List<SearchHit> diversified = hits;
        if (ragProperties.getMmr().isEnabled()) {
            int topK = intValue(context.get(HxprDocumentRetriever.CTX_TOP_K), ragProperties.getDefaultTopK());
            diversified = diversitySelector.select(hits, topK);
        }

        List<SearchHit> reranked = rerankService.rerank(retrievalQuery, diversified);
        List<SearchHit> rerankedHits = reranked != null ? reranked : List.of();

        log.info("Retrieve phase complete: {} chunks retrieved in {}ms (diversified={}, reranked={})",
                hits.size(), searchTimeMs, diversified.size(), rerankedHits.size());

        trace(context).ifPresent(t -> t.record(retrievalQuery, searchTimeMs, rerankedHits));
        return new Retrieval(rerankedHits);
    }

    /** Rebuilds the user message with conversation history + grounded document context. */
    private ChatClientRequest augmentRequest(ChatClientRequest request, Retrieval retrieval) {
        String question = userText(request);
        String historyBlock = stringParam(request.context().get(PARAM_HISTORY_BLOCK), "");
        String contextBlock = assembleContext(retrieval.hits());
        String augmentedUserText = buildUserPrompt(question, historyBlock, contextBlock);

        Prompt augmentedPrompt = request.prompt().augmentUserMessage(
                userMessage -> userMessage.mutate().text(augmentedUserText).build());

        log.debug("Augment phase: context length={} chars, {} sources, historyBlock={} chars",
                contextBlock.length(), retrieval.hits().size(), historyBlock.length());

        return request.mutate().prompt(augmentedPrompt).build();
    }

    private ChatClientResponse fallbackResponse(ChatClientRequest request) {
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(NO_CONTEXT_ANSWER))));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(Map.copyOf(request.context()))
                .build();
    }

    // ------------------------------------------------------------------
    // Context + prompt assembly (ported verbatim from the previous RagService)
    // ------------------------------------------------------------------

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

    private String buildUserPrompt(String question, String history, String context) {
        String conversationSection = history == null || history.isBlank() ? "(none)" : history;
        return String.format("""
                Based on the conversation history and document context, answer the current question.

                --- CONVERSATION HISTORY ---
                %s
                --- END CONVERSATION HISTORY ---

                --- DOCUMENT CONTEXT ---
                %s
                --- END CONTEXT ---

                Question: %s

                Answer:""",
                conversationSection, context, question);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String userText(ChatClientRequest request) {
        UserMessage userMessage = request.prompt().getUserMessage();
        return userMessage != null && userMessage.getText() != null ? userMessage.getText() : "";
    }

    private static java.util.Optional<RetrievalTrace> trace(Map<String, Object> context) {
        Object value = context.get(RetrievalTrace.PARAM_KEY);
        return value instanceof RetrievalTrace t ? java.util.Optional.of(t) : java.util.Optional.empty();
    }

    private static String stringParam(Object value, String fallback) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private record Retrieval(List<SearchHit> hits) {
    }
}
