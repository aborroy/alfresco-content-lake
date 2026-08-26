package org.hyland.filesystem.contentlake.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.model.ContentLakeIngestProperties;
import org.hyland.contentlake.spi.ContentSourceClient;
import org.hyland.contentlake.spi.PermissionRule;
import org.hyland.contentlake.spi.SecurityConfig;
import org.hyland.contentlake.spi.SourceNode;
import org.hyland.filesystem.contentlake.config.FileSystemProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link ContentSourceClient} over a local or mounted filesystem directory.
 *
 * <p>Node ids are absolute, normalized path strings. Text extraction is source-agnostic
 * ({@code TikaTextExtractor}), so no server-side transform service is required. The filesystem has no
 * native ACL model, so every node is stamped with the configured read principals.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class FileSystemSourceClient implements ContentSourceClient {

    private final FileSystemProperties properties;

    @Override
    public String getSourceType() {
        return "filesystem";
    }

    @Override
    public String getSourceId() {
        return properties.getSourceId();
    }

    /** The configured root as an absolute node id, for discovery to start from. */
    public String getRootNodeId() {
        return Path.of(properties.getRootPath()).toAbsolutePath().normalize().toString();
    }

    @Override
    public SourceNode getNode(String nodeId) {
        Path path = Path.of(nodeId);
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return toSourceNode(path, attrs);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read filesystem node: " + nodeId, e);
        }
    }

    @Override
    public List<SourceNode> getChildren(String containerId, int skip, int maxItems) {
        Path dir = Path.of(containerId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.sorted()
                    .skip(Math.max(0, skip))
                    .limit(Math.max(0, maxItems))
                    .map(this::toSourceNodeQuietly)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list directory: " + containerId, e);
        }
    }

    @Override
    public Resource downloadContent(String nodeId, String fileName) {
        return new FileSystemResource(Path.of(nodeId));
    }

    @Override
    public byte[] getContent(String nodeId) {
        try {
            return Files.readAllBytes(Path.of(nodeId));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file content: " + nodeId, e);
        }
    }

    private SourceNode toSourceNodeQuietly(Path path) {
        try {
            return toSourceNode(path, Files.readAttributes(path, BasicFileAttributes.class));
        } catch (IOException e) {
            log.warn("Skipping unreadable filesystem entry {}: {}", path, e.getMessage());
            return null;
        }
    }

    private SourceNode toSourceNode(Path path, BasicFileAttributes attrs) {
        Path normalized = path.toAbsolutePath().normalize();
        String nodeId = normalized.toString();
        boolean folder = attrs.isDirectory();
        String name = normalized.getFileName() != null ? normalized.getFileName().toString() : nodeId;
        Path parent = normalized.getParent();
        String parentPath = parent != null ? parent.toString() : nodeId;
        String mimeType = folder ? null : probeMimeType(normalized);
        OffsetDateTime modifiedAt = attrs.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC);

        Set<String> readPrincipals = properties.getReadPrincipals();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put(ContentLakeIngestProperties.SOURCE_NODE_ID, nodeId);
        props.put(ContentLakeIngestProperties.SOURCE_TYPE, "filesystem");
        props.put(ContentLakeIngestProperties.SOURCE_NAME, name);
        props.put(ContentLakeIngestProperties.SOURCE_PATH, folder ? nodeId : parentPath);
        props.put(ContentLakeIngestProperties.SOURCE_MIME_TYPE, mimeType);
        props.put(ContentLakeIngestProperties.SOURCE_MODIFIED_AT, modifiedAt.toString());
        props.values().removeIf(Objects::isNull);

        return new SourceNode(
                nodeId,
                properties.getSourceId(),
                "filesystem",
                name,
                folder ? nodeId : parentPath,
                mimeType,
                modifiedAt,
                folder,
                readPrincipals,
                Set.of(),
                props,
                buildSecurityConfig(readPrincipals));
    }

    private static SecurityConfig buildSecurityConfig(Set<String> readPrincipals) {
        List<PermissionRule> rules = new ArrayList<>();
        if (readPrincipals != null) {
            for (String principal : readPrincipals) {
                if (principal == null || principal.isBlank()) {
                    continue;
                }
                String type = principal.startsWith("GROUP_") || "__Everyone__".equals(principal) ? "group" : "user";
                rules.add(new PermissionRule(principal, type, principal, "READ"));
            }
        }
        // The filesystem exposes no inheritance model; the configured principals apply uniformly.
        return new SecurityConfig(true, rules);
    }

    private static String probeMimeType(Path path) {
        try {
            return Files.probeContentType(path);
        } catch (IOException e) {
            return null;
        }
    }
}
