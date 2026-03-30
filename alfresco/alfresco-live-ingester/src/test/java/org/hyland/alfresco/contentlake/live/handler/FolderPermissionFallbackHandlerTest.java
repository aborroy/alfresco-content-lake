package org.hyland.alfresco.contentlake.live.handler;

import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;
import org.alfresco.repo.event.v1.model.Resource;
import org.hyland.alfresco.contentlake.live.service.LiveEventProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FolderPermissionFallbackHandlerTest {

    @Mock
    private LiveEventProcessor processor;

    @Mock
    private RepoEvent<DataAttributes<Resource>> event;

    @Mock
    private DataAttributes<Resource> data;

    private FolderPermissionFallbackHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FolderPermissionFallbackHandler(processor);
    }

    @Test
    void permissionLikeFolderUpdateFilter_matchesAclOnlyFolderUpdateShape() {
        when(event.getData()).thenReturn(data);
        when(data.getResource()).thenReturn(folderSnapshot("HR", Map.of("cm:title", (Serializable) "HR")));
        when(data.getResourceBefore()).thenReturn(folderSnapshot("HR", Map.of("cm:title", (Serializable) "HR")));

        assertThat(FolderPermissionLikeUpdateFilter.get().test(event)).isTrue();
    }

    @Test
    void permissionLikeFolderUpdateFilter_matchesFolderUpdateWithoutPreviousSnapshot() {
        when(event.getData()).thenReturn(data);
        when(data.getResource()).thenReturn(folderSnapshot("HR", Map.of("cm:title", (Serializable) "HR")));
        when(data.getResourceBefore()).thenReturn(null);

        assertThat(FolderPermissionLikeUpdateFilter.get().test(event)).isTrue();
    }

    @Test
    void permissionLikeFolderUpdateFilter_rejectsRenameWhenPreviousSnapshotIsPresent() {
        when(event.getData()).thenReturn(data);
        when(data.getResource()).thenReturn(folderSnapshot("HR-renamed", Map.of("cm:title", (Serializable) "HR")));
        when(data.getResourceBefore()).thenReturn(folderSnapshot("HR", Map.of("cm:title", (Serializable) "HR")));

        assertThat(FolderPermissionLikeUpdateFilter.get().test(event)).isFalse();
    }

    @Test
    void handlerFilter_rejectsIndexedScopePropertyChanges() {
        when(event.getData()).thenReturn(data);
        when(data.getResource()).thenReturn(folderSnapshot("HR", Map.of("cl:excludeFromLake", (Serializable) Boolean.TRUE)));
        when(data.getResourceBefore()).thenReturn(folderSnapshot("HR", Map.of("cm:title", (Serializable) "HR")));

        assertThat(handler.getEventFilter().test(event)).isFalse();
    }

    @Test
    void handleEvent_delegatesToPermissionProcessor() {
        handler.handleEvent(event);

        verify(processor).processPermissionUpdate(event);
    }

    private NodeResource folderSnapshot(String name, Map<String, Serializable> properties) {
        return NodeResource.builder()
                .setId("folder-1")
                .setPrimaryHierarchy(List.of("root", "folder-1"))
                .setName(name)
                .setNodeType("cm:folder")
                .setIsFile(false)
                .setIsFolder(true)
                .setModifiedAt(ZonedDateTime.parse("2026-03-30T10:00:00Z"))
                .setProperties(properties)
                .setAspectNames(Set.of("cm:titled"))
                .setPrimaryAssocQName("cm:contains")
                .build();
    }
}
