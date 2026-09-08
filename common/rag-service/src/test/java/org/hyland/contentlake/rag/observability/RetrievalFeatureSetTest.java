package org.hyland.contentlake.rag.observability;

import org.hyland.contentlake.rag.config.HybridSearchProperties;
import org.hyland.contentlake.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code rag.features}, the single low-cardinality tag that stands in for roughly twenty
 * togglable retrieval features (#116).
 */
class RetrievalFeatureSetTest {

    @Test
    void defaultConfiguration() {
        // named-query-discovery and reformulate are on by default; MCP and feedback are not retrieval
        // features and so are absent.
        assertThat(value(new RagProperties(), new HybridSearchProperties()))
                .isEqualTo("hybrid,max,named-query-discovery,reformulate,rerank-none,rrf");
    }

    @Test
    void isSorted_soOneConfigurationIsOneMetricSeries() {
        // Load-bearing: unsorted, "hybrid,mmr" and "mmr,hybrid" would be two series describing the same
        // deployment, which is the cardinality problem this tag exists to avoid.
        RagProperties rag = new RagProperties();
        rag.getMmr().setEnabled(true);
        rag.getHyde().setEnabled(true);

        String value = value(rag, new HybridSearchProperties());
        String[] parts = value.split(",");
        String[] sorted = parts.clone();
        java.util.Arrays.sort(sorted);
        assertThat(parts).isEqualTo(sorted);
    }

    @Test
    void semanticOnlyDropsTheHybridSpecificMembers() {
        RagProperties rag = new RagProperties();
        rag.setUseHybridSearch(false);

        String value = value(rag, new HybridSearchProperties());
        assertThat(value).contains("semantic");
        assertThat(value).doesNotContain("hybrid").doesNotContain("rrf").doesNotContain("minmax");
    }

    @Test
    void reflectsTheFusionStrategyAndNormalization() {
        HybridSearchProperties hybrid = new HybridSearchProperties();
        hybrid.setStrategy("weighted");
        hybrid.setNormalization("minmax");
        hybrid.setChunkFtsEnabled(true);

        String value = value(new RagProperties(), hybrid);
        assertThat(value).contains("weighted").contains("minmax").contains("chunk-fts");
    }

    @Test
    void reportsTheTeiRerankerWhenAUrlIsSet_evenIfTheLlmRerankerIsAlsoEnabled() {
        // Mirrors RerankServiceConfig's bean selection, where a TEI url always wins.
        RagProperties rag = new RagProperties();
        rag.getReranker().setUrl("http://tei:8081");
        rag.getReranker().setEnabled(true);

        String value = value(rag, new HybridSearchProperties());
        assertThat(value).contains("rerank-tei");
        assertThat(value).doesNotContain("rerank-llm").doesNotContain("rerank-none");
    }

    @Test
    void reportsTheLlmRerankerOnlyWhenThereIsNoUrl() {
        RagProperties rag = new RagProperties();
        rag.getReranker().setEnabled(true);

        assertThat(value(rag, new HybridSearchProperties())).contains("rerank-llm");
    }

    @Test
    void includesEveryEnabledExpansionAndShapingFeature() {
        RagProperties rag = new RagProperties();
        rag.getMultiQuery().setEnabled(true);
        rag.getHyde().setEnabled(true);
        rag.getQueryDecomposition().setEnabled(true);
        rag.getMmr().setEnabled(true);
        rag.getRetrievalGrading().setEnabled(true);
        rag.getRetrieval().getSmallToBig().setEnabled(true);
        rag.getPromptInjection().setDefenseEnabled(true);
        rag.getPromptInjection().setScanEnabled(true);
        rag.getCache().setEnabled(true);
        rag.getAgenticTools().setEnabled(true);
        rag.getCitation().getVerify().setEnabled(true);
        rag.getFilterInference().setCategoryProperty("category");

        String value = value(rag, new HybridSearchProperties());
        assertThat(value.split(",")).contains(
                "multi-query", "hyde", "decompose", "mmr", "grading", "grading-broaden",
                "small-to-big", "injection-defense", "injection-scan", "query-cache",
                "agentic-tools", "verify-citations", "filter-inference-category");
    }

    @Test
    void excludesPerRequestOutcomes_soTheTagStaysConstantForTheProcess() {
        // broadened, cache hit/miss, the grading verdict and inferred filters all vary per request.
        // Including any of them would turn a per-deployment-constant tag into a cardinality problem;
        // they are recorded separately as high-cardinality span attributes instead.
        RagProperties rag = new RagProperties();
        rag.getRetrievalGrading().setEnabled(true);
        rag.getCache().setEnabled(true);

        String value = value(rag, new HybridSearchProperties());
        assertThat(value).doesNotContain("broadened");
        assertThat(value).doesNotContain("cache-hit").doesNotContain("cache-miss");
        assertThat(value).doesNotContain("relevant").doesNotContain("weak");
    }

    @Test
    void isComputedOnceAndReturnedUnchanged() {
        RetrievalFeatureSet features =
                new RetrievalFeatureSet(new RagProperties(), new HybridSearchProperties());

        assertThat(features.value()).isEqualTo(features.value());
    }

    private static String value(RagProperties rag, HybridSearchProperties hybrid) {
        return new RetrievalFeatureSet(rag, hybrid).value();
    }
}
