package org.hyland.filesystem.contentlake.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * Configuration for the filesystem source connector (bound from {@code filesystem.*}).
 */
@Data
@ConfigurationProperties(prefix = "filesystem")
public class FileSystemProperties {

    /** Absolute root directory to ingest from (a local path or a mounted volume). */
    private String rootPath;

    /** Source alias stored as the {@code <sourceId>} half of {@code cin_sourceId}. */
    private String sourceId = "filesystem";

    /**
     * Principals granted read access to ingested files. The filesystem has no native ACL model, so
     * every ingested file is stamped with these. Defaults to everyone.
     */
    private Set<String> readPrincipals = Set.of("__Everyone__");

    /**
     * File extensions to include (lower-case, without the dot), e.g. {@code [pdf, docx, txt]}. Empty
     * means include all files.
     */
    private List<String> includeExtensions = List.of();

    /**
     * Path fragments that exclude a file or directory when contained in its absolute path (e.g.
     * {@code [.git, node_modules]}). Hidden entries (name starting with {@code .}) are always skipped.
     */
    private List<String> excludePatterns = List.of();

    /** Page size for directory listing. */
    private int pageSize = 100;
}
