package org.hyland.contentlake.rag.observability;

import org.hyland.contentlake.rag.config.HybridSearchProperties;
import org.hyland.contentlake.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The sorted, comma-joined set of retrieval features that configuration has enabled, recorded on a
 * span as {@code rag.features} (#116).
 *
 * <h3>Why one joined string rather than a boolean per feature</h3>
 * <p>There are roughly twenty togglable retrieval features. As individual low-cardinality tags they
 * would multiply the meter registry's series count by up to 2^20; as high-cardinality attributes they
 * would be invisible to metrics and answerable only span by span, which defeats "which features fired"
 * as an aggregate question. A bitmask is one tag but unreadable in a trace UI and breaks silently the
 * first time someone reorders a bit.</p>
 *
 * <p>A joined list's <em>realised</em> cardinality is the number of distinct configurations actually
 * deployed, which is a handful, because every member comes from a startup-time flag. Sorting is
 * load-bearing: unsorted, {@code hybrid,mmr} and {@code mmr,hybrid} would be two series describing one
 * configuration.</p>
 *
 * <h3>What is deliberately excluded</h3>
 * <p>Per-request outcomes: whether the relevance gate broadened, whether the query cache hit, the
 * grading verdict, and whether filters were inferred. Including them would turn a value that is
 * constant for the process into one that varies per request, and with it the metric tag into a
 * cardinality problem. They are recorded separately as high-cardinality span attributes.</p>
 *
 * <p>Computed once at construction, so the per-request cost is a field read.</p>
 */
@Component
public class RetrievalFeatureSet {

    private final String value;

    public RetrievalFeatureSet(RagProperties rag, HybridSearchProperties hybrid) {
        this.value = compute(rag, hybrid);
    }

    /** The joined feature set, for example {@code hybrid,minmax,mmr,rerank-tei,weighted}. */
    public String value() {
        return value;
    }

    private static String compute(RagProperties rag, HybridSearchProperties hybrid) {
        List<String> features = new ArrayList<>();

        if (rag.isUseHybridSearch()) {
            features.add("hybrid");
            features.add(switch (String.valueOf(hybrid.getStrategy()).toLowerCase()) {
                case "weighted" -> "weighted";
                default -> "rrf";
            });
            features.add(switch (String.valueOf(hybrid.getNormalization()).toLowerCase()) {
                case "minmax" -> "minmax";
                case "max" -> "max";
                default -> "norm-none";
            });
            if (hybrid.isChunkFtsEnabled()) {
                features.add("chunk-fts");
            }
        } else {
            features.add("semantic");
        }

        if (rag.getConversation().isQueryReformulation()) {
            features.add("reformulate");
        }
        if (rag.getMultiQuery().isEnabled()) {
            features.add("multi-query");
        }
        if (rag.getHyde().isEnabled()) {
            features.add("hyde");
        }
        if (rag.getQueryDecomposition().isEnabled()) {
            features.add("decompose");
        }
        if (rag.getMmr().isEnabled()) {
            features.add("mmr");
        }

        // Mirrors RerankServiceConfig's bean selection: a TEI url always wins, the LLM reranker only
        // applies when there is no url, and otherwise reranking is a no-op.
        String rerankerUrl = rag.getReranker().getUrl();
        if (rerankerUrl != null && !rerankerUrl.isBlank()) {
            features.add("rerank-tei");
        } else if (rag.getReranker().isEnabled()) {
            features.add("rerank-llm");
        } else {
            features.add("rerank-none");
        }

        if (rag.getRetrievalGrading().isEnabled()) {
            features.add("grading");
            if (rag.getRetrievalGrading().isBroaden()) {
                features.add("grading-broaden");
            }
        }
        if (rag.getRetrieval().getSmallToBig().isEnabled()) {
            features.add("small-to-big");
        }
        if (rag.getNamedQuery().getDiscovery().isEnabled()) {
            features.add("named-query-discovery");
        }
        String categoryProperty = rag.getFilterInference().getCategoryProperty();
        if (categoryProperty != null && !categoryProperty.isBlank()) {
            features.add("filter-inference-category");
        }
        if (rag.getPromptInjection().isDefenseEnabled()) {
            features.add("injection-defense");
        }
        if (rag.getPromptInjection().isScanEnabled()) {
            features.add("injection-scan");
        }
        if (rag.getCache().isEnabled()) {
            features.add("query-cache");
        }
        if (rag.getAgenticTools().isEnabled()) {
            features.add("agentic-tools");
        }
        if (rag.getCitation().getVerify().isEnabled()) {
            features.add("verify-citations");
        }

        Collections.sort(features);
        return String.join(",", features);
    }
}
