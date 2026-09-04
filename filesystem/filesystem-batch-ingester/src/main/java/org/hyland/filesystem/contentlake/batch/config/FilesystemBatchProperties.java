package org.hyland.filesystem.contentlake.batch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Batch runtime and embedding settings for the filesystem full ingester.
 */
@Data
@ConfigurationProperties(prefix = "filesystem.batch")
public class FilesystemBatchProperties {

    private Executor executor = new Executor();
    private Embedding embedding = new Embedding();
    private Security security = new Security();

    /**
     * Credentials for the ingester's own REST API. A filesystem source has no user directory to
     * authenticate against, so a single configured account guards the sync trigger.
     *
     * <p>Both fields are deliberately without defaults. {@code FilesystemBatchSecurityConfig} fails
     * startup when either is blank, so the service can neither run open nor run unreachable behind a
     * password generated at boot.</p>
     */
    @Data
    public static class Security {
        private String username;
        private String password;
    }

    @Data
    public static class Executor {
        private int coreSize = 1;
        private int maxSize = 1;
        private int queueCapacity = 1000;
        private int awaitTerminationSeconds = 30;
    }

    @Data
    public static class Embedding {
        private int minChunkSize = 200;
        private int chunkSize = 1000;
        private int chunkOverlap = 120;
        private double similarityThreshold = 0.75;
        private String modelName = "default";
        private NoiseReduction noiseReduction = new NoiseReduction();
    }

    @Data
    public static class NoiseReduction {
        private boolean enabled = true;
        private boolean aggressive = false;
    }
}
