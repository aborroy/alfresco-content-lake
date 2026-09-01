package org.hyland.contentlake.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HXPR (Content Lake) connection and authentication properties.
 *
 * <p>Bound from {@code hxpr.*} in {@code application.yml}.
 * Shared by both batch-ingester and live-ingester via
 * {@code @EnableConfigurationProperties(HxprProperties.class)}.</p>
 */
@Data
@ConfigurationProperties(prefix = "hxpr")
public class HxprProperties {

    private String url = "http://localhost:8080";
    private String repositoryId = "default";

    /** HTTP Basic credentials for the ai-ready-index engine (filestore user store). */
    private String username;
    private String password;

    /**
     * Base target path in HXPR where Alfresco structures are created.
     *
     * <p>The full hierarchy is built as:
     * {@code {targetPath}/{pathRepositoryId or alfrescoRepositoryId}/{alfrescoPath}}.</p>
     */
    private String targetPath = "/alfresco-sync";

    /**
     * Optional repository-id override used only for path prefixing in Content Lake.
     *
     * <p>When empty, the ingester falls back to the Alfresco Discovery repository id.
     * Set this when HXPR permissions require a fixed writable path prefix.</p>
     */
    private String pathRepositoryId;

    /** Knowledge-graph provisioning (hxpr Graph API). Disabled by default. */
    private GraphConfig graph = new GraphConfig();

    /**
     * GraphRAG foundation: ensures a graphDB, a base ontology, and an ontology route exist in
     * hxpr at startup. Bound from {@code hxpr.graph.*}.
     */
    @Data
    public static class GraphConfig {

        /** Master switch. When false, no graph beans are created and provisioning never runs. */
        private boolean enabled = false;

        /**
         * Resolved graphDB id. Usually left empty and resolved by name at startup; set it to pin a
         * pre-existing graphDB.
         */
        private String graphdbId;

        /** Name of the content-lake graphDB. */
        private String graphdbName = "content-lake";

        /** hxpr schema version label. {@code v2} selects the ACL-aware Dgraph schema. */
        private String version = "v2";

        /** Name of the base ontology registered in hxpr. */
        private String ontologyName = "content-lake-base";

        /** Description stored with the base ontology. */
        private String ontologyDescription =
                "Content Lake base ontology: Document, Person, Organization, Location, Concept.";

        /** Classpath (or file) location of the YAML ontology uploaded when none is registered. */
        private String ontologyResource = "classpath:graph/content-lake-ontology.yaml";

        /**
         * hxpr {@code ExpressionVisitor} condition selecting which content is routed to the base
         * ontology. Defaults to all file content.
         */
        private String routeCondition = "content.sys_primaryType == \"SysFile\"";

        /**
         * Entity extraction at ingestion (#54). When {@code true} (and {@link #enabled} is true),
         * ingesters extract entities from document text and populate the graph.
         */
        private boolean extractionEnabled = true;

        /** Maximum number of entities upserted per document (caps LLM noise and graph writes). */
        private int maxEntitiesPerDocument = 30;

        /** Maximum characters of document text sent to the extraction LLM. */
        private int maxExtractionChars = 12000;

        /**
         * Runs entity extraction off the ingest hot path (#83). When {@code true} (default),
         * {@code GraphIngestionService.ingestAsync} submits the extract -> link work to a bounded
         * executor and returns immediately, so document ingestion latency is not dominated by the
         * extraction LLM call and graph population becomes eventually consistent. Set {@code false}
         * to run extraction inline on the ingest thread (deterministic; used by tests).
         */
        private boolean extractionAsync = true;

        /** Worker threads for the async extraction executor. */
        private int extractionWorkerThreads = 2;

        /**
         * Bounded queue capacity for pending async extraction tasks. On saturation the submitting
         * ingest thread runs the task inline (CallerRuns backpressure) rather than dropping it.
         */
        private int extractionQueueCapacity = 500;
    }
}
