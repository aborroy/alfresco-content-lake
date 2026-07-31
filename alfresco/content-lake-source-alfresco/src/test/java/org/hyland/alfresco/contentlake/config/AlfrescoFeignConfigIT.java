package org.hyland.alfresco.contentlake.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.alfresco.core.handler.NodesApi;
import org.alfresco.core.model.NodeEntry;
import org.alfresco.search.handler.SearchApi;
import org.alfresco.search.model.RequestQuery;
import org.alfresco.search.model.RequestPagination;
import org.alfresco.search.model.ResultSetPaging;
import org.alfresco.search.model.SearchRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ground-truth check that {@link AlfrescoFeignConfig}'s manually-built {@link SearchApi} Feign
 * client actually sends the POST {@code SearchRequest} body and decodes the {@code ResultSetPaging}
 * response. Uses a JDK {@link HttpServer} stub so no Alfresco stack is needed.
 *
 * <p>Motivation: E2E showed batch discovery ({@code SearchApi.search}) returning 0 results even
 * when the docs were indexed. {@code AlfrescoSearchService} swallows exceptions, so a broken Feign
 * client is indistinguishable from Solr-lag. This test isolates the wiring.</p>
 */
class AlfrescoFeignConfigIT {

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedContentType = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();

    private static final String SEARCH_RESPONSE = """
            {"list":{"pagination":{"count":1,"hasMoreItems":false,"totalItems":1,"skipCount":0,"maxItems":100},
            "entries":[{"entry":{"id":"abc-123","name":"doc.txt","nodeType":"cm:content","isFolder":false,"isFile":true}}]}}
            """;

    // Includes createdAt/modifiedAt (OffsetDateTime on the ACS Node model) so decoding fails unless
    // the Feign ObjectMapper has the JSR-310 module registered (regression guard for issue #78).
    private static final String NODE_RESPONSE = """
            {"entry":{"id":"folder-1","name":"content-lake-test","nodeType":"cm:folder","isFolder":true,"isFile":false,
            "createdAt":"2026-07-31T14:19:26.154+0000","modifiedAt":"2026-07-31T14:22:10.364+0000",
            "aspectNames":["cl:indexed"]}}
            """;

    private static final String PLAIN_CONTENT = "MEMORANDUM\nThis is plain text content, not JSON.";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange ex) -> {
            String path = ex.getRequestURI().toString();
            capturedPath.set(path);
            capturedContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            capturedAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            try (InputStream in = ex.getRequestBody()) {
                capturedBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            // Route by path: /content endpoints return raw text (binary body), /nodes/{id} returns
            // node JSON, everything else returns the search JSON.
            String contentType = "application/json";
            String body;
            if (path.contains("/content")) {
                body = PLAIN_CONTENT;
                contentType = "text/plain";
            } else if (path.contains("/nodes/")) {
                body = NODE_RESPONSE;
            } else {
                body = SEARCH_RESPONSE;
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private SearchApi buildSearchApi() {
        // Point search.service.path at "" so the stub sees the raw /search method path.
        AlfrescoFeignConfig config = new AlfrescoFeignConfig(
                baseUrl(), "/content", "/discovery", "", "admin", "admin");
        return config.searchApi(
                config.alfrescoFeignEncoder(),
                config.alfrescoFeignDecoder(),
                config.alfrescoBasicAuthRequestInterceptor());
    }

    private NodesApi buildNodesApi() {
        // Empty content path so the stub sees the raw /nodes/{nodeId} method path.
        AlfrescoFeignConfig config = new AlfrescoFeignConfig(
                baseUrl(), "", "/discovery", "/search", "admin", "admin");
        return config.nodesApi(
                config.alfrescoFeignEncoder(),
                config.alfrescoFeignDecoder(),
                config.alfrescoBasicAuthRequestInterceptor());
    }

    @Test
    void searchApi_sendsJsonBody_andDecodesResponse() {
        SearchApi searchApi = buildSearchApi();

        SearchRequest request = new SearchRequest()
                .query(new RequestQuery()
                        .language(RequestQuery.LanguageEnum.AFTS)
                        .query("ANCESTOR:'workspace://SpacesStore/folder-1' AND TYPE:'cm:content'"))
                .paging(new RequestPagination().maxItems(100).skipCount(0));

        ResponseEntity<ResultSetPaging> response = searchApi.search(request);

        // 1. The response body decoded correctly (ResponseEntityDecoder + JacksonDecoder).
        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getList()).isNotNull();
        assertThat(response.getBody().getList().getPagination().getTotalItems()).isEqualTo(1);
        assertThat(response.getBody().getList().getEntries()).hasSize(1);
        assertThat(response.getBody().getList().getEntries().get(0).getEntry().getId()).isEqualTo("abc-123");

        // 2. The request actually carried the JSON query body (the crux of the E2E discovery=0 bug).
        assertThat(capturedBody.get())
                .as("SearchApi must POST the SearchRequest as a JSON body")
                .isNotBlank()
                .contains("\"query\"")
                .contains("ANCESTOR:")
                .contains("cm:content");

        // 3. Correct content type, path, and basic auth applied.
        assertThat(capturedContentType.get()).contains("application/json");
        assertThat(capturedPath.get()).endsWith("/search");
        assertThat(capturedAuth.get()).startsWith("Basic ");
    }

    @Test
    void nodesApi_getNode_encodesPathVariableAndListParams_andDecodes() {
        NodesApi nodesApi = buildNodesApi();

        // Mirrors AlfrescoClient.getAlfrescoNode: getNode(nodeId, INCLUDE, null, null)
        // where INCLUDE = List.of("properties","path","permissions").
        ResponseEntity<NodeEntry> response = nodesApi.getNode(
                "folder-1", List.of("properties", "path", "permissions"), null, null);

        // Response decodes (a decode failure here == the E2E "Folder not found" false negative).
        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getEntry()).isNotNull();
        assertThat(response.getBody().getEntry().getId()).isEqualTo("folder-1");
        assertThat(response.getBody().getEntry().isIsFolder()).isTrue();
        // OffsetDateTime decoded => JSR-310 module is registered on the Feign ObjectMapper.
        assertThat(response.getBody().getEntry().getCreatedAt()).isNotNull();

        // The @PathVariable nodeId must land in the path (not be dropped/misplaced), and the
        // multi-value include list must be sent as a query param.
        assertThat(capturedPath.get())
                .as("nodeId @PathVariable must be substituted into /nodes/{nodeId}")
                .startsWith("/nodes/folder-1");
        assertThat(capturedPath.get())
                .as("include list @RequestParam must be present")
                .contains("include=");
        assertThat(capturedAuth.get()).startsWith("Basic ");
    }

    @Test
    void nodesApi_getNodeContent_returnsRawContent_notParsedAsJson() throws Exception {
        NodesApi nodesApi = buildNodesApi();

        // getNodeContent returns ResponseEntity<Resource> (the raw file body). A plain-text body
        // must NOT be run through the JSON decoder (that was the #79 "Unrecognized token" failure
        // that aborted text extraction -> zero embeddings).
        ResponseEntity<Resource> response = nodesApi.getNodeContent("folder-1", true, null, null);

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();
        String content = new String(response.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).isEqualTo(PLAIN_CONTENT);
        assertThat(capturedPath.get()).startsWith("/nodes/folder-1/content");
    }
}
