package org.hyland.alfresco.contentlake.batch.service;

import org.alfresco.core.model.Node;
import org.hyland.alfresco.contentlake.batch.config.IngestionProperties;
import org.hyland.alfresco.contentlake.batch.model.BatchSyncRequest;
import org.hyland.alfresco.contentlake.client.AlfrescoClient;
import org.hyland.alfresco.contentlake.client.AlfrescoSearchService;
import org.hyland.alfresco.contentlake.service.ContentLakeScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the issue #78 discovery retry: recursive {@code ANCESTOR:} discovery re-runs while the
 * query returns empty (Solr commit lag) and stops as soon as descendants appear.
 */
@ExtendWith(MockitoExtension.class)
class NodeDiscoveryServiceTest {

    @Mock AlfrescoClient alfrescoClient;
    @Mock AlfrescoSearchService searchService;
    @Mock ContentLakeScopeResolver scopeResolver;

    private IngestionProperties props;
    private NodeDiscoveryService service;

    @BeforeEach
    void setUp() {
        props = new IngestionProperties();
        props.getDiscovery().setMaxAttempts(3);
        props.getDiscovery().setRetryIntervalMs(0); // no real sleep in tests
        service = new NodeDiscoveryService(alfrescoClient, searchService, props, scopeResolver);

        // Folder resolves and already has cl:indexed (so ensureIndexedAndResolve is a no-op update).
        Node folder = new Node();
        folder.setId("folder-1");
        folder.setIsFolder(true);
        folder.setAspectNames(List.of(ContentLakeScopeResolver.INDEXED_ASPECT));
        when(alfrescoClient.getAlfrescoNode("folder-1")).thenReturn(folder);
        when(scopeResolver.getExcludedAspects()).thenReturn(Set.of());
    }

    private static Node file(String id) {
        Node n = new Node();
        n.setId(id);
        n.setIsFolder(false);
        n.setNodeType("cm:content");
        return n;
    }

    private static BatchSyncRequest recursiveRequest() {
        BatchSyncRequest req = new BatchSyncRequest();
        req.setFolders(List.of("folder-1"));
        req.setRecursive(true);
        req.setTypes(List.of("cm:content"));
        return req;
    }

    @Test
    void discoverNodes_retriesWhileEmpty_thenReturnsDescendants() {
        // Empty on the first two attempts (Solr ANCESTOR not committed yet), then one file.
        when(searchService.findDescendantFiles(anyString(), any()))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(file("doc-1")));

        List<Node> discovered = service.discoverNodes(recursiveRequest()).toList();

        assertThat(discovered).extracting(Node::getId).containsExactly("doc-1");
        verify(searchService, times(3)).findDescendantFiles(anyString(), any());
    }

    @Test
    void discoverNodes_stopsRetrying_onFirstNonEmptyResult() {
        when(searchService.findDescendantFiles(anyString(), any()))
                .thenReturn(List.of(file("doc-1")));

        List<Node> discovered = service.discoverNodes(recursiveRequest()).toList();

        assertThat(discovered).hasSize(1);
        verify(searchService, times(1)).findDescendantFiles(anyString(), any());
    }

    @Test
    void discoverNodes_genuinelyEmptyFolder_exhaustsAttemptsAndReturnsEmpty() {
        when(searchService.findDescendantFiles(anyString(), any())).thenReturn(List.of());

        List<Node> discovered = service.discoverNodes(recursiveRequest()).toList();

        assertThat(discovered).isEmpty();
        verify(searchService, times(3)).findDescendantFiles(anyString(), any());
    }
}
