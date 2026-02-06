package org.alfresco.contentlake.batch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for ingestion discovery, filtering, transformation and embedding.
 *
 * <p>Bound from {@code ingestion.*} in {@code application.yml}.
 */
@Data
@ConfigurationProperties(prefix = "ingestion")
public class IngestionProperties {

    private List<Source> sources = new ArrayList<>();
    private Exclude exclude = new Exclude();
    private Transform transform = new Transform();
    private Embedding embedding = new Embedding();

    @Data
    public static class Source {
        private String folder;
        private boolean recursive = true;
        private List<String> types = new ArrayList<>();
        private List<String> mimeTypes = new ArrayList<>();
    }

    @Data
    public static class Exclude {
        private List<String> paths = new ArrayList<>();
        private List<String> aspects = new ArrayList<>();
    }

    @Data
    public static class Transform {
        private int workerThreads = 4;
        private int queueCapacity = 1000;
    }

    @Data
    public static class Embedding {
        private int chunkSize = 900;
        private int chunkOverlap = 120;
        private String modelName = "default";
    }
}