package org.hyland.filesystem.contentlake.service;

import lombok.RequiredArgsConstructor;
import org.hyland.contentlake.spi.ScopeResolver;
import org.hyland.contentlake.spi.SourceNode;
import org.hyland.filesystem.contentlake.config.FileSystemProperties;

import java.util.List;
import java.util.Locale;

/**
 * {@link ScopeResolver} for the filesystem connector.
 *
 * <p>Files are in scope when they match the configured include-extensions (if any) and are not
 * excluded. Directories are traversed unless hidden or matching an exclusion pattern.</p>
 */
@RequiredArgsConstructor
public class FileSystemScopeResolver implements ScopeResolver {

    private final FileSystemProperties properties;

    @Override
    public boolean isInScope(SourceNode node) {
        if (node.folder()) {
            return false;
        }
        if (isExcluded(node)) {
            return false;
        }
        List<String> includeExtensions = properties.getIncludeExtensions();
        if (includeExtensions == null || includeExtensions.isEmpty()) {
            return true;
        }
        String ext = extensionOf(node.name());
        return ext != null && includeExtensions.stream().anyMatch(e -> e.equalsIgnoreCase(ext));
    }

    @Override
    public boolean shouldTraverse(SourceNode node) {
        return node.folder() && !isExcluded(node);
    }

    private boolean isExcluded(SourceNode node) {
        String name = node.name();
        if (name != null && name.startsWith(".")) {
            return true;
        }
        String path = node.nodeId();
        List<String> excludePatterns = properties.getExcludePatterns();
        if (path == null || excludePatterns == null || excludePatterns.isEmpty()) {
            return false;
        }
        return excludePatterns.stream()
                .filter(p -> p != null && !p.isBlank())
                .anyMatch(path::contains);
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT)
                : null;
    }
}
