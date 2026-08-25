package org.hyland.contentlake.client;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.model.graph.GraphDbPageResponse;
import org.hyland.contentlake.model.graph.GraphDbRequest;
import org.hyland.contentlake.model.graph.GraphDbResponse;
import org.hyland.contentlake.model.graph.GraphEntityUpsert;
import org.hyland.contentlake.model.graph.GraphEntityUpsertResponse;
import org.hyland.contentlake.model.graph.GraphQueryRequest;
import org.hyland.contentlake.model.graph.GraphQueryResult;
import org.hyland.contentlake.model.graph.GraphRelationshipUpsert;
import org.hyland.contentlake.model.graph.OntologyPageResponse;
import org.hyland.contentlake.model.graph.OntologyResponse;
import org.hyland.contentlake.model.graph.OntologyRoute;
import org.hyland.contentlake.model.graph.OntologyRoutesRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Business-logic layer over the hxpr Graph REST API.
 *
 * <p>Wraps {@link HxprGraphApi} for the JSON endpoints and uses the shared {@code RestClient}
 * directly for the multipart ontology upload. All lookups are name-based so callers can be
 * idempotent (list-before-write).</p>
 */
@Slf4j
public class HxprGraphService {

    /** hxpr paginates graphDBs/ontologies; content-lake registers a single DB and ontology. */
    private static final long PAGE_LIMIT = 1000L;
    private static final String ONTOLOGIES_PATH = "/api/graph/ontologies";
    private static final MediaType YAML = MediaType.parseMediaType("application/x-yaml");

    private final HxprGraphApi graphApi;
    private final RestClient restClient;

    /** Cache of the resolved graphDB id so ingestion does not list graphDBs on every document. */
    private volatile String resolvedGraphDbId;

    public HxprGraphService(HxprGraphApi graphApi, RestClient restClient) {
        this.graphApi = graphApi;
        this.restClient = restClient;
    }

    // ------------------------------------------------------------------
    // GraphDB
    // ------------------------------------------------------------------

    public Optional<GraphDbResponse> findGraphDbByName(String graphDbName) {
        return listGraphDbs().stream()
                .filter(db -> graphDbName != null && graphDbName.equals(db.getGraphDBName()))
                .findFirst();
    }

    public Optional<GraphDbResponse> findGraphDbById(String graphDbId) {
        return listGraphDbs().stream()
                .filter(db -> graphDbId != null && graphDbId.equals(db.getGraphDBId()))
                .findFirst();
    }

    public GraphDbResponse createGraphDb(String graphDbName, String version) {
        log.info("Creating hxpr graphDB '{}' (version {})", graphDbName, version);
        return graphApi.createGraphDb(new GraphDbRequest(graphDbName, version));
    }

    private List<GraphDbResponse> listGraphDbs() {
        GraphDbPageResponse page = graphApi.listGraphDbs(PAGE_LIMIT);
        return page == null ? List.of() : page.getGraphDBs();
    }

    // ------------------------------------------------------------------
    // Ontology
    // ------------------------------------------------------------------

    public Optional<OntologyResponse> findOntologyByName(String ontologyName) {
        OntologyPageResponse page = graphApi.listOntologies(PAGE_LIMIT);
        List<OntologyResponse> ontologies = page == null ? List.of() : page.getOntologies();
        return ontologies.stream()
                .filter(o -> ontologyName != null && ontologyName.equals(o.getOntologyName()))
                .findFirst();
    }

    /**
     * Uploads a YAML ontology via {@code POST /api/graph/ontologies} (multipart/form-data).
     * hxpr accepts only {@code .yaml}/{@code .yml} files, so the file part name ends in {@code .yaml}.
     */
    public OntologyResponse uploadOntology(String ontologyName, String description, Resource yamlFile) {
        log.info("Uploading hxpr ontology '{}' from {}", ontologyName, yamlFile.getDescription());
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("ontologyName", ontologyName);
        if (description != null && !description.isBlank()) {
            builder.part("description", description);
        }
        String filename = yamlFile.getFilename();
        builder.part("file", yamlFile)
                .filename(filename != null ? filename : ontologyName + ".yaml")
                .contentType(YAML);

        MultiValueMap<String, HttpEntity<?>> body = builder.build();
        return restClient.post()
                .uri(ONTOLOGIES_PATH)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(OntologyResponse.class);
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    public void setOntologyRoutes(String graphDbId, List<OntologyRoute> routes) {
        log.info("Setting {} ontology route(s) on graphDB {}", routes.size(), graphDbId);
        graphApi.setOntologyRoutes(graphDbId, new OntologyRoutesRequest(routes));
    }

    // ------------------------------------------------------------------
    // Entities / relationships / query (GraphRAG population and traversal)
    // ------------------------------------------------------------------

    /**
     * Upserts entities and returns a {@code clientRef -> uid} map (order-independent correlation).
     */
    public Map<String, String> upsertEntities(String graphDbId, List<GraphEntityUpsert> entities) {
        List<GraphEntityUpsertResponse> response = graphApi.upsertEntities(graphDbId, entities);
        Map<String, String> byClientRef = new LinkedHashMap<>();
        if (response != null) {
            for (GraphEntityUpsertResponse r : response) {
                if (r.getClientRef() != null) {
                    byClientRef.put(r.getClientRef(), r.getUid());
                }
            }
        }
        return byClientRef;
    }

    public void upsertRelationships(String graphDbId, List<GraphRelationshipUpsert> relationships) {
        if (relationships == null || relationships.isEmpty()) {
            return;
        }
        graphApi.upsertRelationships(graphDbId, relationships);
    }

    /** Runs a Dgraph GraphQL query and returns the raw {@code rows[0].result} JSON string (or null). */
    public String query(String graphDbId, String query, Map<String, String> vars) {
        GraphQueryResult result = graphApi.query(graphDbId, new GraphQueryRequest(query, vars));
        return result != null ? result.firstResultJson() : null;
    }

    /**
     * Resolves the graphDB id for a caller that did not run provisioning: prefers a configured id,
     * else looks it up by name and caches it. Returns {@code null} if it cannot be resolved.
     */
    public String resolveGraphDbId(String configuredId, String graphDbName) {
        if (configuredId != null && !configuredId.isBlank()) {
            return configuredId;
        }
        String cached = resolvedGraphDbId;
        if (cached != null) {
            return cached;
        }
        String id = findGraphDbByName(graphDbName).map(GraphDbResponse::getGraphDBId).orElse(null);
        if (id != null) {
            resolvedGraphDbId = id;
        }
        return id;
    }
}
