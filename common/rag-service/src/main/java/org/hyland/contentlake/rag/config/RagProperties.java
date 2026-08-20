package org.hyland.contentlake.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for RAG behaviour.
 *
 * <p>Bound from {@code rag.*} in {@code application.yml}.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** Default number of chunks to retrieve for context. */
    private int defaultTopK = 5;

    /** Default minimum similarity score threshold. 0.0 = no filtering (lets SemanticSearchService apply its own threshold). */
    private double defaultMinScore = 0.0;

    /** When true, uses hybrid (vector + keyword) search for RAG retrieval; false uses vector-only. */
    private boolean useHybridSearch = true;

    /**
     * Maximum character length of the assembled context sent to the LLM.
     *
     * <p>The previous default of 4000 chars (~1000 tokens) was very conservative
     * and often caused useful chunks to be truncated. Even small local models
     * like llama3.2 support 128K token context windows. 12000 chars (~3000 tokens)
     * allows 5-8 substantial chunks while leaving ample room for the system prompt,
     * user question, and generated answer within any reasonable model's limits.</p>
     */
    private int maxContextLength = 12000;

    /**
     * Default system prompt for the LLM.
     *
     * <p>Structured to work well with smaller local models by providing explicit
     * formatting guidance and step-by-step instructions for citation.</p>
     */
    private String defaultSystemPrompt = """
            You are a document assistant that answers questions based strictly on the provided context.

            RULES:
            1. Use ONLY information from the DOCUMENT CONTEXT below. Do not use prior knowledge.
            2. When referencing information, cite the source using its label (e.g. "According to Source 1..."). \
            Cite each source once per answer, not once per sentence.
            3. If multiple sources contain relevant information, synthesize them and cite each.
            4. Extract facts directly from the context. If a name, date, number, code, or identifier \
            appears in any source, state it directly. Do not claim information is missing if it is present \
            in any source.
            5. Partial answers are valuable. If you can answer part of the question from the context, do so, \
            then note what is genuinely missing. Do not refuse the whole question because one detail is unclear.
            6. Be concise and direct. Do not repeat the question or add unnecessary preamble.
            7. You may apply standard world knowledge for unit conversions (temperatures, currencies, UTC offsets) \
            when the document context provides the underlying fact but not the converted value. \
            Do not invent document facts.""";

    /** Cross-encoder reranker settings (disabled when url is blank). */
    private RerankerProperties reranker = new RerankerProperties();

    /** Max Marginal Relevance diversity-selection settings (disabled by default). */
    private MmrProperties mmr = new MmrProperties();

    /** Conversation memory settings. */
    private ConversationProperties conversation = new ConversationProperties();

    /** Source-specific deep-link templates returned in search and RAG responses. */
    private SourceLinkProperties sourceLinks = new SourceLinkProperties();

    /** Shared bounds for the query-expansion stage that multi-query, HyDE and decomposition feed. */
    private QueryExpansionProperties queryExpansion = new QueryExpansionProperties();

    /** Multi-query retrieval settings (disabled by default). */
    private MultiQueryProperties multiQuery = new MultiQueryProperties();

    /** Hypothetical Document Embedding settings (disabled by default). */
    private HydeProperties hyde = new HydeProperties();

    /** Compound-question decomposition settings (disabled by default). */
    private QueryDecompositionProperties queryDecomposition = new QueryDecompositionProperties();

    /** Pre-generation retrieval relevance gate (disabled by default). */
    private RetrievalGradingProperties retrievalGrading = new RetrievalGradingProperties();

    @Data
    public static class RerankerProperties {

        /** TEI cross-encoder endpoint (e.g. http://localhost:8081). Leave blank to disable reranking. */
        private String url = "";

        /** Number of top results to keep after reranking. */
        private int topN = 8;

        /**
         * Enables LLM-based reranking when no TEI {@code url} is set. Escape hatch: when false
         * (default) and no url is set, reranking is a no-op. Ignored when a TEI url is present
         * (the TEI reranker always takes precedence).
         */
        private boolean enabled = false;
    }

    @Data
    public static class MmrProperties {

        /** Enables MMR diversity selection between retrieval and reranking. */
        private boolean enabled = false;

        /**
         * Trade-off between relevance and diversity. 1.0 = pure relevance (original order),
         * 0.0 = pure diversity.
         */
        private double lambda = 0.5;

        /** Size of the over-retrieved candidate pool MMR selects from. */
        private int poolSize = 30;
    }

    @Data
    public static class ConversationProperties {

        /** Enables/disables conversation memory features. */
        private boolean enabled = true;

        /** Number of most recent turns kept in memory per session. */
        private int maxHistoryTurns = 10;

        /** Session expiration timeout based on inactivity. */
        private int sessionTtlMinutes = 30;

        /** Enables/disables conversation-aware query reformulation. */
        private boolean queryReformulation = true;
    }

    @Data
    public static class SourceLinkProperties {

        /** Share-style deep link for Alfresco documents. */
        private String alfrescoTemplate =
                "${content.service.url}/share/page/document-details?nodeRef=workspace://SpacesStore/{nodeId}";

        /** Default Nuxeo Web UI browse link using the full repository path. */
        private String nuxeoTemplate =
                "${nuxeo.base-url}/ui/#!/browse{nuxeoPath}";
    }

    @Data
    public static class QueryExpansionProperties {

        /**
         * Hard cap on the total number of query variants a single search may run, counting the
         * original. Multi-query, HyDE and decomposition all append to the same list, so without a
         * shared ceiling enabling all three multiplies the per-request embedding and hxpr calls.
         */
        private int maxVariants = 6;

        /**
         * Reciprocal-rank-fusion constant used when merging the per-variant result sets.
         *
         * <p>Distinct from {@code search.hybrid.rrf-k}, which fuses the vector and keyword legs of a
         * single variant. This one fuses across variants, one level up.</p>
         */
        private int rrfK = 60;
    }

    @Data
    public static class MultiQueryProperties {

        /** Enables multi-query retrieval: N LLM-generated query variants, fused by RRF. */
        private boolean enabled = false;

        /** Number of variants to request from the LLM, excluding the original query. */
        private int variants = 3;
    }

    @Data
    public static class HydeProperties {

        /**
         * Enables Hypothetical Document Embedding: the LLM drafts an answer-shaped passage which is
         * embedded document-side (no query instruction prefix) and searched alongside the original
         * query.
         */
        private boolean enabled = false;

        /** Upper bound on the generated passage, truncated before embedding. */
        private int maxChars = 1000;
    }

    @Data
    public static class QueryDecompositionProperties {

        /** Enables decomposition of compound questions into independently-retrievable sub-questions. */
        private boolean enabled = false;

        /** Upper bound on the sub-questions accepted from the LLM. */
        private int maxSubQuestions = 4;
    }

    @Data
    public static class RetrievalGradingProperties {

        /** Enables the pre-generation relevance gate on the reranked hits. */
        private boolean enabled = false;

        /**
         * Score a hit must reach to count as relevant.
         *
         * <p>This is <strong>not</strong> a cosine value on the default hybrid path: fused scores sit
         * on the fusion's own scale, roughly 0.02-0.03 under {@code rrf} and 0-1 under
         * {@code weighted}/{@code minmax}. The default of 0.0 leaves the gate inert even when
         * enabled, so a threshold has to be chosen against the configured fusion strategy.</p>
         */
        private double minScore = 0.0;

        /** Number of hits that must clear {@code minScore} for the verdict to be relevant. */
        private int minHits = 1;

        /**
         * When true, a weak verdict triggers one broadened re-retrieval pass (threshold dropped,
         * candidate pool widened) before generation is skipped. When false, a weak verdict skips
         * generation immediately.
         */
        private boolean broaden = true;
    }
}
