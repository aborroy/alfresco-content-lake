package org.hyland.alfresco.contentlake.batch.config;

import lombok.Data;
import org.hyland.contentlake.service.ReconcileProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for ingestion discovery, filtering, transformation and embedding.
 *
 * <p>Bound from {@code ingestion.*} in {@code application.yml}.</p>
 */
@Data
@ConfigurationProperties(prefix = "ingestion")
public class IngestionProperties {

    private List<Source> sources = new ArrayList<>();
    private Exclude exclude = new Exclude();
    private Transform transform = new Transform();
    private Embedding embedding = new Embedding();
    private Discovery discovery = new Discovery();

    /** Post-discovery reconciliation sweep (#115). Off by default. */
    private ReconcileProperties reconcile = new ReconcileProperties();

    /**
     * Discovery-time retry to absorb the Solr ANCESTOR-commit lag (issue #78).
     *
     * <p>Recursive discovery finds descendants with an AFTS {@code ANCESTOR:} query. The
     * {@code ANCESTOR} (transitive path) relationship and a just-added {@code cl:indexed} folder
     * aspect commit to Solr later than the direct {@code PARENT} relationship, so a freshly
     * uploaded/onboarded folder can transiently return 0 descendants. To avoid silently ingesting
     * nothing, discovery re-runs the query on an empty result up to {@code maxAttempts} times,
     * waiting {@code retryIntervalMs} between attempts. A genuinely empty folder simply exhausts the
     * (bounded) attempts and proceeds with 0.</p>
     */
    @Data
    public static class Discovery {
        /**
         * Max attempts for the recursive ANCESTOR discovery query when it returns empty (>=1).
         * Default 10 x {@code retryIntervalMs} (~30s) comfortably exceeds the observed Solr
         * ANCESTOR-commit lag on a cold stack (~25s); a genuinely empty folder just exhausts these.
         */
        private int maxAttempts = 10;
        /** Delay between empty-result discovery attempts, in milliseconds. */
        private long retryIntervalMs = 3000;
    }

    @Data
    public static class Source {
        private String folder;
        private boolean recursive = true;
        private List<String> types = new ArrayList<>();
    }

    @Data
    public static class Exclude {
        private List<String> paths = new ArrayList<>();
        private List<String> aspects = new ArrayList<>();
    }

    @Data
    public static class Transform {
        private int workerThreads = 4;
        private int queueCapacity = 1000;
    }

    @Data
    public static class Embedding {
        /** Minimum chunk size in characters; short paragraphs are merged up to this floor. */
        private int minChunkSize = 200;
        /** Maximum chunk size in characters; maps to {@code ChunkingConfig.maxChunkSize}. */
        private int chunkSize = 1000;
        /** Overlap between consecutive chunks in characters. */
        private int chunkOverlap = 120;
        /** Cosine-similarity threshold for adaptive chunk merging (0.0–1.0). */
        private double similarityThreshold = 0.75;
        private String modelName = "default";
        private NoiseReduction noiseReduction = new NoiseReduction();
    }

    @Data
    public static class NoiseReduction {
        private boolean enabled = true;
        private boolean aggressive = false;
    }
}
