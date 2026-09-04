package org.hyland.contentlake.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.ContentLakeIngestProperties;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.model.HxprTermsAggregationResult;
import org.hyland.contentlake.security.AclFilterBuilder;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchRequest;
import org.hyland.contentlake.rag.model.SemanticSearchResponse;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Agentic tool-calling surface for the RAG LLM (#65): a small set of {@code @Tool} methods the model
 * may invoke mid-generation to fetch more evidence when the initial retrieval pass is insufficient.
 *
 * <p><strong>ACL safety.</strong> Tools take only <em>search</em> parameters - never a caller/LLM
 * -supplied principal. Identity is captured from the authenticated {@link SecurityContextHolder} on
 * the request thread by {@code RagService} and passed via {@link ToolContext} ({@link #CTX_AUTH}).
 * Each tool restores that {@link Authentication} onto the executing thread (which, on the streaming
 * path, may be a Reactor scheduler thread with no inherited context) before delegating, so every
 * tool-invoked retrieval goes through the same {@code sys_racl} filtering as the initial pass. A
 * tool can therefore never widen the caller's access, even under prompt injection.</p>
 *
 * <p>Stateless singleton: all per-request state (auth, iteration counter) lives in the
 * {@link ToolContext}, so the bean is safe to share across concurrent requests.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagToolset {

    /** ToolContext key holding the request's {@link Authentication}. */
    public static final String CTX_AUTH = "cl.tool.auth";
    /** ToolContext key holding the per-request {@link AtomicInteger} research-round counter. */
    public static final String CTX_ITERATIONS = "cl.tool.iterations";

    private static final int TOOL_TOP_K = 5;
    private static final int MAX_SOURCE_BUCKETS = 50;
    private static final int MAX_DOCUMENT_TEXT_CHARS = 4000;

    private final SemanticSearchService semanticSearchService;
    private final HxprService hxprService;
    private final RagProperties ragProperties;

    @Tool(description = "Search the document corpus again with a refined query when the current context "
            + "is insufficient to answer. Returns the top matching chunks with their source names.")
    public String researchAgain(
            @ToolParam(description = "The refined natural-language search query") String query,
            @ToolParam(description = "Optional HXQL filter to scope the search; leave empty for none", required = false)
            String filter,
            ToolContext toolContext) {

        int max = ragProperties.getAgenticTools().getMaxIterations();
        AtomicInteger iterations = iterations(toolContext);
        if (iterations != null && iterations.incrementAndGet() > max) {
            return "No further research permitted (reached the maximum of " + max
                    + " additional retrieval rounds). Answer from the context already provided.";
        }

        return withAuth(toolContext, () -> {
            SemanticSearchRequest request = SemanticSearchRequest.builder()
                    .query(query)
                    .topK(TOOL_TOP_K)
                    .filter(emptyToNull(filter))
                    .build();
            SemanticSearchResponse response = semanticSearchService.search(request);
            List<SearchHit> hits = response.getResults();
            log.info("Agentic tool researchAgain(query=\"{}\") -> {} hits", query, hits != null ? hits.size() : 0);
            return formatHits(hits);
        });
    }

    @Tool(description = "Fetch the stored text and metadata for a single document by its id. Returns the "
            + "document only if the current user is permitted to read it.")
    public String getDocument(
            @ToolParam(description = "The document id (cin_id) to fetch") String documentId,
            ToolContext toolContext) {

        return withAuth(toolContext, () -> {
            String escaped = AclFilterBuilder.escapeLiteral(documentId);
            // ACL-safe: reuse the current user's permission filter and AND the id predicate; never a
            // raw sys_id/cin_id query, which would bypass sys_racl.
            String hxql = semanticSearchService.currentUserPermissionFilter(null, "cin_id = '" + escaped + "'");
            HxprDocument.QueryResult result = hxprService.query(hxql, 1, 0);
            int matches = result != null && result.getDocuments() != null ? result.getDocuments().size() : 0;
            log.info("Agentic tool getDocument(id={}) -> {} accessible match(es)", documentId, matches);
            if (matches == 0) {
                return "No accessible document found with id " + documentId + ".";
            }
            return formatDocument(result.getDocuments().get(0));
        });
    }

    @Tool(description = "List the distinct sources (repositories) the current user can retrieve documents "
            + "from, with the document count for each.")
    public String listSources(ToolContext toolContext) {
        return withAuth(toolContext, () -> {
            String hxql = semanticSearchService.currentUserPermissionFilter(null, null);
            HxprTermsAggregationResult agg =
                    hxprService.termsAggregation(hxql, "cin_sourceId", null, MAX_SOURCE_BUCKETS);
            log.info("Agentic tool listSources() -> {} source(s)",
                    agg != null && agg.getAggregationsBuckets() != null ? agg.getAggregationsBuckets().size() : 0);
            if (agg == null || agg.getAggregationsBuckets() == null || agg.getAggregationsBuckets().isEmpty()) {
                return "No accessible sources found.";
            }
            StringBuilder sb = new StringBuilder("Accessible sources (id: document count):\n");
            for (HxprTermsAggregationResult.Bucket bucket : agg.getAggregationsBuckets()) {
                sb.append("- ").append(bucket.getKey()).append(": ").append(bucket.getDocCount()).append('\n');
            }
            return sb.toString().trim();
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Runs {@code action} with the request's captured {@link Authentication} set on the current
     * thread, restoring the prior context afterwards. This works regardless of whether the tool runs
     * on the request thread (sync path) or a Reactor scheduler thread (stream path).
     */
    private String withAuth(ToolContext toolContext, Supplier<String> action) {
        Authentication auth = auth(toolContext);
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            if (auth != null) {
                SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(auth);
                SecurityContextHolder.setContext(ctx);
            }
            return action.get();
        } catch (Exception e) {
            log.warn("Agentic tool call failed: {}", e.getMessage());
            return "Tool call failed: " + e.getMessage();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static Authentication auth(ToolContext toolContext) {
        Object value = toolContext != null ? toolContext.getContext().get(CTX_AUTH) : null;
        return value instanceof Authentication a ? a : null;
    }

    private static AtomicInteger iterations(ToolContext toolContext) {
        Object value = toolContext != null ? toolContext.getContext().get(CTX_ITERATIONS) : null;
        return value instanceof AtomicInteger i ? i : null;
    }

    private static String formatHits(List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "No additional documents matched.";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (SearchHit hit : hits) {
            String name = hit.getSourceDocument() != null && hit.getSourceDocument().getName() != null
                    ? hit.getSourceDocument().getName()
                    : "Unknown document";
            String docId = hit.getSourceDocument() != null ? hit.getSourceDocument().getDocumentId() : null;
            sb.append("[Result ").append(i++).append(": ").append(name);
            if (docId != null) {
                sb.append(" (id: ").append(docId).append(')');
            }
            sb.append("]\n").append(hit.getChunkText() != null ? hit.getChunkText() : "").append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String formatDocument(HxprDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(doc.getSysName()).append('\n');
        sb.append("Source: ").append(doc.getCinSourceId()).append('\n');
        Object text = doc.getCinIngestProperties() != null
                ? doc.getCinIngestProperties().get(ContentLakeIngestProperties.CONTENT_LAKE_EXTRACTED_TEXT)
                : null;
        if (text != null) {
            String s = text.toString();
            if (s.length() > MAX_DOCUMENT_TEXT_CHARS) {
                s = s.substring(0, MAX_DOCUMENT_TEXT_CHARS) + "\n... (truncated)";
            }
            sb.append("Text:\n").append(s);
        } else {
            sb.append("Text: (no extracted text available)");
        }
        return sb.toString();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
