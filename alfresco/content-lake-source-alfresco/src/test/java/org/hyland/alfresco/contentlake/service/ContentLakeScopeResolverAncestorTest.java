package org.hyland.alfresco.contentlake.service;

import org.alfresco.core.model.Node;
import org.alfresco.core.model.PathElement;
import org.alfresco.core.model.PathInfo;
import org.hyland.alfresco.contentlake.client.AlfrescoClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ancestor-scope checks in ContentLakeScopeResolver.
 *
 * Scope is resolved by walking the node's own path elements with one
 * {@code GET /nodes/{id}} per element: the file's own node ID is never part of the
 * ancestor set, and the search index is never consulted (a custom model's aspects and
 * properties are not guaranteed to be indexed, and an index read would race the commit
 * of the very aspect change that triggered the check).
 */
class ContentLakeScopeResolverAncestorTest {

    private StubAlfrescoClient alfrescoClient;
    private ContentLakeScopeResolver resolver;

    @BeforeEach
    void setUp() {
        alfrescoClient = new StubAlfrescoClient();
        resolver = new ContentLakeScopeResolver(List.of(), Set.of(), alfrescoClient);
    }

    @Test
    void isInScope_returnsTrueWhenAncestorHasIndexedAspect() {
        Node file = fileWithPath("file-1", List.of("root-id", "sites-id", "doclib-id"));
        indexedFolder("doclib-id");

        assertThat(resolver.isInScope(file)).isTrue();
    }

    @Test
    void isInScope_returnsFalseWhenNoAncestorHasIndexedAspect() {
        Node file = fileWithPath("file-1", List.of("root-id", "sites-id", "doclib-id"));
        plainFolder("root-id");
        plainFolder("sites-id");
        plainFolder("doclib-id");

        assertThat(resolver.isInScope(file)).isFalse();
    }

    @Test
    void isInScope_returnsFalseWhenAncestorIsExcluded() {
        Node file = fileWithPath("file-1", List.of("root-id", "sites-id", "doclib-id"));
        indexedFolder("doclib-id");
        excludedFolder("sites-id");

        assertThat(resolver.isInScope(file)).isFalse();
    }

    @Test
    void isInScope_returnsTrueWhenFileHasIndexedAspectDirectly() {
        Node file = fileWithPath("file-1", List.of("root-id"));
        file.aspectNames(List.of("cl:indexed"));
        // No ancestor indexed, but the file itself has cl:indexed -- unusual but supported

        assertThat(resolver.isInScope(file)).isTrue();
    }

    @Test
    void isInScope_ignoresTheNodesOwnId() {
        // Guard against regression: the walk must use ancestor path element IDs,
        // NOT the file's own node ID.
        Node file = fileWithPath("file-node-id", List.of("ancestor-a"));
        alfrescoClient.nodesById.put("file-node-id", folderWithAspects("file-node-id", List.of("cl:indexed")));
        plainFolder("ancestor-a");

        assertThat(resolver.isInScope(file)).isFalse();
        assertThat(alfrescoClient.requestedIds).containsOnly("ancestor-a");
    }

    @Test
    void hasIndexedAncestor_walksPathElementsViaRest() {
        Node folder = folderWithPath("folder-1", List.of("root-id", "parent-id"));
        indexedFolder("parent-id");
        plainFolder("root-id");

        assertThat(resolver.hasIndexedAncestor(folder)).isTrue();
    }

    @Test
    void isFolderInScope_returnsTrueViaAncestorIndex() {
        Node folder = folderWithAspects("folder-1", List.of()); // no self cl:indexed
        folder.path(new PathInfo()
                .elements(List.of(new PathElement().id("ancestor-1").name("Sites")))
                .name("/Sites/...")
                .isComplete(true));
        indexedFolder("ancestor-1");

        assertThat(resolver.isFolderInScope(folder)).isTrue();
    }

    @Test
    void isFolderInScope_returnsFalseWhenNoSelfNorAncestorIndex() {
        Node folder = folderWithAspects("folder-1", List.of());
        folder.path(new PathInfo()
                .elements(List.of(new PathElement().id("ancestor-1").name("Sites")))
                .name("/Sites/...")
                .isComplete(true));
        plainFolder("ancestor-1");

        assertThat(resolver.isFolderInScope(folder)).isFalse();
    }

    @Test
    void isFolderInScope_returnsFalseWhenSelfExcludedFromLake() {
        Node folder = folderWithAspects("folder-1", List.of("cl:indexed", "cl:fileScope"));
        folder.properties(Map.of("cl:excludeFromLake", true));
        folder.path(new PathInfo().elements(List.of()).name("/Sites/...").isComplete(true));

        assertThat(resolver.isFolderInScope(folder)).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private void indexedFolder(String nodeId) {
        alfrescoClient.nodesById.put(nodeId, folderWithAspects(nodeId, List.of("cl:indexed")));
    }

    private void plainFolder(String nodeId) {
        alfrescoClient.nodesById.put(nodeId, folderWithAspects(nodeId, List.of()));
    }

    private void excludedFolder(String nodeId) {
        alfrescoClient.nodesById.put(nodeId,
                folderWithAspects(nodeId, List.of("cl:fileScope")).properties(Map.of("cl:excludeFromLake", true)));
    }

    private static Node fileWithPath(String nodeId, List<String> ancestorIds) {
        return new Node()
                .id(nodeId)
                .isFolder(false)
                .isFile(true)
                .path(pathOf(ancestorIds));
    }

    private static Node folderWithPath(String nodeId, List<String> ancestorIds) {
        return new Node()
                .id(nodeId)
                .isFolder(true)
                .isFile(false)
                .path(pathOf(ancestorIds));
    }

    private static PathInfo pathOf(List<String> ancestorIds) {
        List<PathElement> elements = ancestorIds.stream()
                .map(id -> new PathElement().id(id).name("name-" + id))
                .toList();
        return new PathInfo()
                .elements(elements)
                .name("/Company Home/...")
                .isComplete(true);
    }

    private static Node folderWithAspects(String nodeId, List<String> aspects) {
        return new Node()
                .id(nodeId)
                .isFolder(true)
                .isFile(false)
                .aspectNames(aspects);
    }

    private static final class StubAlfrescoClient extends AlfrescoClient {
        final Map<String, Node> nodesById = new HashMap<>();
        final List<String> requestedIds = new java.util.ArrayList<>();

        StubAlfrescoClient() {
            super(null, null);
        }

        @Override
        public String getSourceId() { return "test-repo"; }

        @Override
        public String getRepositoryId() { return "test-repo"; }

        @Override
        public Node getAlfrescoNode(String nodeId) {
            requestedIds.add(nodeId);
            return nodesById.get(nodeId);
        }
    }
}
