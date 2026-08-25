package org.hyland.contentlake.client;

import org.hyland.contentlake.model.graph.GraphDbPageResponse;
import org.hyland.contentlake.model.graph.GraphDbRequest;
import org.hyland.contentlake.model.graph.GraphDbResponse;
import org.hyland.contentlake.model.graph.GraphEntityUpsert;
import org.hyland.contentlake.model.graph.GraphEntityUpsertResponse;
import org.hyland.contentlake.model.graph.GraphQueryRequest;
import org.hyland.contentlake.model.graph.GraphQueryResult;
import org.hyland.contentlake.model.graph.OntologyResponse;
import org.hyland.contentlake.model.graph.OntologyRoute;
import org.hyland.contentlake.model.graph.OntologyRoutesRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(MockitoExtension.class)
class HxprGraphServiceTest {

    @Mock
    private HxprGraphApi graphApi;

    @Test
    void findGraphDbByName_returnsMatchingEntry() {
        HxprGraphService service = new HxprGraphService(graphApi, mock(RestClient.class));
        when(graphApi.listGraphDbs(anyLong())).thenReturn(page(db("gdb-1", "other"), db("gdb-2", "content-lake")));

        Optional<GraphDbResponse> found = service.findGraphDbByName("content-lake");

        assertThat(found).isPresent();
        assertThat(found.get().getGraphDBId()).isEqualTo("gdb-2");
    }

    @Test
    void findGraphDbByName_returnsEmptyWhenAbsent() {
        HxprGraphService service = new HxprGraphService(graphApi, mock(RestClient.class));
        when(graphApi.listGraphDbs(anyLong())).thenReturn(page(db("gdb-1", "other")));

        assertThat(service.findGraphDbByName("content-lake")).isEmpty();
    }

    @Test
    void createGraphDb_sendsNameAndVersion() {
        HxprGraphService service = new HxprGraphService(graphApi, mock(RestClient.class));
        when(graphApi.createGraphDb(any())).thenReturn(db("gdb-new", "content-lake"));

        service.createGraphDb("content-lake", "v2");

        ArgumentCaptor<GraphDbRequest> captor = ArgumentCaptor.forClass(GraphDbRequest.class);
        verify(graphApi).createGraphDb(captor.capture());
        assertThat(captor.getValue().getGraphDBName()).isEqualTo("content-lake");
        assertThat(captor.getValue().getVersion()).isEqualTo("v2");
    }

    @Test
    void setOntologyRoutes_wrapsRoutesInRequest() {
        HxprGraphService service = new HxprGraphService(graphApi, mock(RestClient.class));

        service.setOntologyRoutes("gdb-1", List.of(new OntologyRoute("content.sys_primaryType == \"SysFile\"", "ont-1")));

        ArgumentCaptor<OntologyRoutesRequest> captor = ArgumentCaptor.forClass(OntologyRoutesRequest.class);
        verify(graphApi).setOntologyRoutes(eq("gdb-1"), captor.capture());
        assertThat(captor.getValue().getOntologyRoutes()).hasSize(1);
        assertThat(captor.getValue().getOntologyRoutes().get(0).getOntologyId()).isEqualTo("ont-1");
    }

    @Test
    void uploadOntology_postsMultipartToOntologiesEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        HxprGraphService service = new HxprGraphService(graphApi, restClient);

        server.expect(requestTo("/api/graph/ontologies"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ontologyId\":\"ont-1\",\"ontologyName\":\"content-lake-base\"}"));

        OntologyResponse response = service.uploadOntology(
                "content-lake-base", "desc", new ClassPathResource("graph/content-lake-ontology.yaml"));

        assertThat(response.getOntologyId()).isEqualTo("ont-1");
        server.verify();
    }

    @Test
    void upsertEntities_returnsClientRefToUidMap() {
        HxprGraphService service = new HxprGraphService(graphApi, mock(RestClient.class));
        when(graphApi.upsertEntities(eq("gdb-1"), any())).thenReturn(List.of(
                entResp("doc", "0xdoc"), entResp("e0", "0xe0")));

        var uids = service.upsertEntities("gdb-1", List.of(
                new GraphEntityUpsert(null, "doc", "Document", java.util.Map.of("documentId", "sys-1"))));

        assertThat(uids).containsEntry("doc", "0xdoc").containsEntry("e0", "0xe0");
    }

    @Test
    void query_returnsFirstRowResultJson() {
        HxprGraphService service = new HxprGraphService(graphApi, mock(RestClient.class));
        GraphQueryResult result = new GraphQueryResult();
        result.setRows(List.of(java.util.Map.of("result", "{\"queryGlobalEntity\":[]}")));
        when(graphApi.query(eq("gdb-1"), any(GraphQueryRequest.class))).thenReturn(result);

        String json = service.query("gdb-1", "query {}", java.util.Map.of());

        assertThat(json).isEqualTo("{\"queryGlobalEntity\":[]}");
    }

    @Test
    void resolveGraphDbId_prefersConfiguredId_elseLooksUpByNameAndCaches() {
        HxprGraphService service = new HxprGraphService(graphApi, mock(RestClient.class));
        // Configured id short-circuits (no listing).
        assertThat(service.resolveGraphDbId("gdb-cfg", "content-lake")).isEqualTo("gdb-cfg");

        // No configured id -> look up by name, then cache (second call must not list again).
        when(graphApi.listGraphDbs(anyLong())).thenReturn(page(db("gdb-2", "content-lake")));
        assertThat(service.resolveGraphDbId(null, "content-lake")).isEqualTo("gdb-2");
        assertThat(service.resolveGraphDbId(null, "content-lake")).isEqualTo("gdb-2");
        verify(graphApi).listGraphDbs(anyLong());  // exactly once (cached thereafter)
    }

    // -- helpers -------------------------------------------------------------

    private static GraphEntityUpsertResponse entResp(String clientRef, String uid) {
        GraphEntityUpsertResponse r = new GraphEntityUpsertResponse();
        r.setClientRef(clientRef);
        r.setUid(uid);
        return r;
    }

    private static GraphDbResponse db(String id, String name) {
        GraphDbResponse db = new GraphDbResponse();
        db.setGraphDBId(id);
        db.setGraphDBName(name);
        return db;
    }

    private static GraphDbPageResponse page(GraphDbResponse... dbs) {
        GraphDbPageResponse page = new GraphDbPageResponse();
        page.setGraphDBs(List.of(dbs));
        return page;
    }
}
