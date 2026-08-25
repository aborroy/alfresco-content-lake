package org.hyland.contentlake.client;

import org.hyland.contentlake.model.graph.GraphDbPageResponse;
import org.hyland.contentlake.model.graph.GraphDbRequest;
import org.hyland.contentlake.model.graph.GraphDbResponse;
import org.hyland.contentlake.model.graph.GraphEntityUpsert;
import org.hyland.contentlake.model.graph.GraphEntityUpsertResponse;
import org.hyland.contentlake.model.graph.GraphQueryRequest;
import org.hyland.contentlake.model.graph.GraphQueryResult;
import org.hyland.contentlake.model.graph.GraphRelationshipUpsert;
import org.hyland.contentlake.model.graph.OntologyPageResponse;
import org.hyland.contentlake.model.graph.OntologyRoutesRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

import java.util.List;

/**
 * Spring HTTP Interface for the hxpr Graph REST API (Dgraph-backed).
 *
 * <p>Covers the JSON endpoints needed for graph-DB provisioning and ontology routing.
 * The multipart ontology upload ({@code POST /api/graph/ontologies}) is issued from
 * {@link HxprGraphService} via {@code RestClient} directly, since Spring HTTP Interfaces
 * do not model multipart bodies cleanly.</p>
 */
@HttpExchange("/api/graph")
public interface HxprGraphApi {

    /** Creates a graphDB. The request {@code version} selects the hxpr schema ({@code v1}/{@code v2}). */
    @PostExchange("/graphdbs")
    GraphDbResponse createGraphDb(@RequestBody GraphDbRequest request);

    /** Lists graphDBs. {@code limit} is passed explicitly so the single content-lake DB is always in the page. */
    @GetExchange("/graphdbs")
    GraphDbPageResponse listGraphDbs(@RequestParam("limit") long limit);

    /** Lists registered ontologies. */
    @GetExchange("/ontologies")
    OntologyPageResponse listOntologies(@RequestParam("limit") long limit);

    /** Replaces the graphDB's ontology routing rules. */
    @PutExchange("/graphdbs/{graphDBId}/ontologyroutes")
    void setOntologyRoutes(@PathVariable("graphDBId") String graphDBId,
                           @RequestBody OntologyRoutesRequest request);

    /** Upserts entities; the response pairs each {@code clientRef} with its assigned Dgraph uid. */
    @PostExchange("/graphdbs/{graphDBId}/entities")
    List<GraphEntityUpsertResponse> upsertEntities(@PathVariable("graphDBId") String graphDBId,
                                                   @RequestBody List<GraphEntityUpsert> entities);

    /** Upserts relationships between entities (by uid). */
    @PostExchange("/graphdbs/{graphDBId}/relationships")
    List<GraphRelationshipUpsert> upsertRelationships(@PathVariable("graphDBId") String graphDBId,
                                                      @RequestBody List<GraphRelationshipUpsert> relationships);

    /** Executes a Dgraph GraphQL query (v2 graphDBs). */
    @PostExchange("/graphdbs/{graphDBId}/query")
    GraphQueryResult query(@PathVariable("graphDBId") String graphDBId,
                           @RequestBody GraphQueryRequest request);
}
