package org.alfresco.contentlake.service;

import org.alfresco.contentlake.client.AlfrescoClient;
import org.alfresco.core.model.Node;
import org.alfresco.core.model.PathElement;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared scope rules used by both ingesters to decide whether a node belongs in
 * Content Lake.
 *
 * <p>Business scope is controlled by {@code cl:indexed} on any ancestor folder.
 * Technical exclusions such as working copies and thumbnail paths are still
 * applied first.</p>
 *
 * <h3>Ancestor scope check</h3>
 * The Alfresco REST API does not populate aspect names on path elements, so ancestor
 * detection requires individual {@code GET /nodes/{id}} calls. Results are cached in
 * a bounded in-memory map (max {@value CACHE_MAX_SIZE} entries). Call
 * {@link #invalidateFolderScope(String)} whenever {@code cl:indexed} is added to or
 * removed from a folder so the cache stays consistent.
 */
public class ContentLakeScopeResolver {

    public static final String INDEXED_ASPECT = "cl:indexed";
    public static final String EXCLUDE_FROM_LAKE_PROPERTY = "cl:excludeFromLake";

    private static final int CACHE_MAX_SIZE = 2000;

    private final List<String> excludedPathPatterns;
    private final Set<String> excludedAspects;
    private final AlfrescoClient alfrescoClient;

    /**
     * Cache: folderId → whether that folder has the {@code cl:indexed} aspect.
     * Bounded to {@value CACHE_MAX_SIZE} entries; invalidated explicitly on scope
     * changes via {@link #invalidateFolderScope(String)}.
     */
    private final ConcurrentHashMap<String, Boolean> folderAspectCache = new ConcurrentHashMap<>(256);

    public ContentLakeScopeResolver(Collection<String> excludedPathPatterns,
                                    Collection<String> excludedAspects,
                                    AlfrescoClient alfrescoClient) {
        this.excludedPathPatterns = List.copyOf(excludedPathPatterns);
        this.excludedAspects = Set.copyOf(excludedAspects);
        this.alfrescoClient = alfrescoClient;
    }

    /**
     * Returns {@code true} when the node should still be traversed during folder
     * discovery. This only applies technical exclusions, not business scope.
     */
    public boolean shouldTraverse(Node node) {
        if (node == null) {
            return false;
        }
        if (hasExcludedAspect(node.getAspectNames())) {
            return false;
        }

        String path = node.getPath() != null ? node.getPath().getName() : null;
        return !matchesExcludedPath(path);
    }

    /**
     * Returns {@code true} when the node is an in-scope file for Content Lake.
     */
    public boolean isInScope(Node node) {
        if (node == null || Boolean.TRUE.equals(node.isIsFolder())) {
            return false;
        }
        if (!shouldTraverse(node)) {
            return false;
        }
        if (isExcludedFromLake(node)) {
            return false;
        }

        return hasIndexedAspect(node.getAspectNames()) || hasIndexedAncestor(node);
    }

    public boolean hasExcludedAspect(Collection<String> aspects) {
        if (aspects == null || aspects.isEmpty()) {
            return false;
        }
        return aspects.stream().anyMatch(excludedAspects::contains);
    }

    public boolean matchesExcludedPath(String path) {
        return matchesAny(path, excludedPathPatterns);
    }

    /**
     * Removes the given folder from the ancestor-scope cache.
     *
     * <p>Must be called after {@code cl:indexed} is added to or removed from a folder
     * so that subsequent {@link #isInScope} calls reflect the updated aspect state.</p>
     */
    public void invalidateFolderScope(String folderId) {
        folderAspectCache.remove(folderId);
    }

    /**
     * Walks the node's path elements and returns {@code true} if any ancestor folder
     * has the {@code cl:indexed} aspect.
     *
     * <p>The Alfresco REST API does not populate aspect names inside path elements, so
     * each unique folder ID is resolved with a {@code GET /nodes/{id}} call. The result
     * is cached to avoid redundant calls when processing many nodes under the same path.</p>
     */
    private boolean hasIndexedAncestor(Node node) {
        if (node.getPath() == null || node.getPath().getElements() == null) {
            return false;
        }

        for (PathElement element : node.getPath().getElements()) {
            if (element.getId() == null) {
                continue;
            }
            if (isFolderIndexed(element.getId())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether the folder with the given ID has {@code cl:indexed}, using the
     * local cache to avoid repeated REST calls.
     */
    private boolean isFolderIndexed(String folderId) {
        Boolean cached = folderAspectCache.get(folderId);
        if (cached != null) {
            return cached;
        }

        Node folder = alfrescoClient.getNode(folderId);
        boolean indexed = folder != null && hasIndexedAspect(folder.getAspectNames());

        // Store only if the cache has not yet reached its capacity limit.
        if (folderAspectCache.size() < CACHE_MAX_SIZE) {
            folderAspectCache.putIfAbsent(folderId, indexed);
        }

        return indexed;
    }

    private boolean hasIndexedAspect(Collection<String> aspects) {
        return aspects != null && aspects.contains(INDEXED_ASPECT);
    }

    private boolean isExcludedFromLake(Node node) {
        Object properties = node.getProperties();
        if (!(properties instanceof Map<?, ?> propertyMap)) {
            return false;
        }

        Object value = propertyMap.get(EXCLUDE_FROM_LAKE_PROPERTY);
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return false;
    }

    private boolean matchesAny(String path, List<String> patterns) {
        if (path == null || path.isBlank() || patterns.isEmpty()) {
            return false;
        }

        for (String pattern : patterns) {
            if (path.matches(pattern.replace("*", ".*"))) {
                return true;
            }
        }

        return false;
    }
}
