package org.hyland.alfresco.contentlake.live.handler;

import org.alfresco.event.sdk.handling.filter.EventFilter;
import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;
import org.alfresco.repo.event.v1.model.Resource;

import java.util.Objects;

final class FolderPermissionLikeUpdateFilter implements EventFilter {

    private static final FolderPermissionLikeUpdateFilter INSTANCE = new FolderPermissionLikeUpdateFilter();

    static EventFilter get() {
        return INSTANCE;
    }

    private FolderPermissionLikeUpdateFilter() {
    }

    @Override
    public boolean test(RepoEvent<DataAttributes<Resource>> event) {
        if (event == null || event.getData() == null) {
            return false;
        }
        if (!(event.getData().getResource() instanceof NodeResource current)) {
            return false;
        }
        if (!Boolean.TRUE.equals(current.isFolder())) {
            return false;
        }

        Resource previousResource = event.getData().getResourceBefore();
        if (previousResource == null) {
            return true;
        }
        if (!(previousResource instanceof NodeResource previous) || !Boolean.TRUE.equals(previous.isFolder())) {
            return false;
        }

        return Objects.equals(current.getId(), previous.getId())
                && Objects.equals(current.getPrimaryHierarchy(), previous.getPrimaryHierarchy())
                && Objects.equals(current.getName(), previous.getName())
                && Objects.equals(current.getNodeType(), previous.getNodeType())
                && Objects.equals(current.isFile(), previous.isFile())
                && Objects.equals(current.isFolder(), previous.isFolder())
                && Objects.equals(current.getContent(), previous.getContent())
                && Objects.equals(current.getPrimaryAssocQName(), previous.getPrimaryAssocQName());
    }
}
