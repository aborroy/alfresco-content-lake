package org.hyland.contentlake.service;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprGraphService;
import org.hyland.contentlake.config.HxprProperties;
import org.hyland.contentlake.config.HxprProperties.GraphConfig;
import org.hyland.contentlake.model.graph.GraphDbResponse;
import org.hyland.contentlake.model.graph.OntologyResponse;
import org.hyland.contentlake.model.graph.OntologyRoute;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.util.List;
import java.util.Optional;

/**
 * Idempotent GraphRAG foundation: guarantees the content-lake graphDB, its base ontology, and an
 * ontology route exist in hxpr.
 *
 * <p>Every step is list-before-write, so re-runs and concurrent app instances are safe. The whole
 * routine is best-effort: failures are logged and swallowed so a graph-provisioning problem never
 * blocks application startup or ingestion.</p>
 */
@Slf4j
public class GraphProvisioningService {

    private final HxprGraphService graphService;
    private final HxprProperties properties;
    private final ResourceLoader resourceLoader;

    public GraphProvisioningService(HxprGraphService graphService,
                                    HxprProperties properties,
                                    ResourceLoader resourceLoader) {
        this.graphService = graphService;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Ensures the graphDB, base ontology, and ontology route exist. Safe to call repeatedly.
     */
    public void ensureGraphProvisioned() {
        GraphConfig cfg = properties.getGraph();
        try {
            String graphDbId = ensureGraphDb(cfg);
            if (graphDbId == null) {
                log.warn("Graph provisioning aborted: could not resolve a graphDB id.");
                return;
            }
            cfg.setGraphdbId(graphDbId);

            String ontologyId = ensureOntology(cfg);
            if (ontologyId == null) {
                log.warn("Graph provisioning: graphDB {} ready but no ontology id; skipping route.", graphDbId);
                return;
            }

            ensureOntologyRoute(cfg, graphDbId, ontologyId);
            log.info("Graph provisioning complete: graphDB={}, ontology={}", graphDbId, ontologyId);
        } catch (RuntimeException e) {
            // Best-effort: never fail startup on a graph-provisioning error.
            log.error("Graph provisioning failed; continuing without graph features.", e);
        }
    }

    /**
     * Resolves the graphDB id: honour a configured id if it exists, else find by name, else create.
     *
     * @return the resolved graphDB id, or {@code null} if it could not be resolved
     */
    private String ensureGraphDb(GraphConfig cfg) {
        String configuredId = cfg.getGraphdbId();
        if (configuredId != null && !configuredId.isBlank()) {
            Optional<GraphDbResponse> existing = graphService.findGraphDbById(configuredId);
            if (existing.isPresent()) {
                log.info("Using configured graphDB id {}", configuredId);
                return configuredId;
            }
            log.warn("Configured graphDB id {} not found; falling back to name lookup.", configuredId);
        }

        Optional<GraphDbResponse> byName = graphService.findGraphDbByName(cfg.getGraphdbName());
        if (byName.isPresent()) {
            log.info("Found existing graphDB '{}' ({})", cfg.getGraphdbName(), byName.get().getGraphDBId());
            return byName.get().getGraphDBId();
        }

        GraphDbResponse created = graphService.createGraphDb(cfg.getGraphdbName(), cfg.getVersion());
        return created != null ? created.getGraphDBId() : null;
    }

    /**
     * Resolves the base ontology id: find by name, else upload the bundled YAML.
     *
     * @return the resolved ontology id, or {@code null} if it could not be resolved
     */
    private String ensureOntology(GraphConfig cfg) {
        Optional<OntologyResponse> existing = graphService.findOntologyByName(cfg.getOntologyName());
        if (existing.isPresent()) {
            log.info("Found existing ontology '{}' ({})", cfg.getOntologyName(), existing.get().getOntologyId());
            return existing.get().getOntologyId();
        }

        Resource ontology = resourceLoader.getResource(cfg.getOntologyResource());
        if (!ontology.exists()) {
            log.warn("Base ontology resource {} not found; cannot upload ontology.", cfg.getOntologyResource());
            return null;
        }
        OntologyResponse uploaded =
                graphService.uploadOntology(cfg.getOntologyName(), cfg.getOntologyDescription(), ontology);
        return uploaded != null ? uploaded.getOntologyId() : null;
    }

    /**
     * Points the base-ontology route at the resolved ontology. The PUT replaces the route list, so
     * this is idempotent regardless of prior state.
     */
    private void ensureOntologyRoute(GraphConfig cfg, String graphDbId, String ontologyId) {
        List<OntologyRoute> routes = List.of(new OntologyRoute(cfg.getRouteCondition(), ontologyId));
        graphService.setOntologyRoutes(graphDbId, routes);
    }
}
