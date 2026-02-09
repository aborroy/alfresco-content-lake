package org.alfresco.contentlake.batch.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.contentlake.batch.config.IngestionProperties;
import org.alfresco.contentlake.batch.model.TransformationTask;
import org.alfresco.contentlake.client.AlfrescoClient;
import org.alfresco.contentlake.client.HxprDocumentApi;
import org.alfresco.contentlake.client.HxprService;
import org.alfresco.contentlake.client.TransformClient;
import org.alfresco.contentlake.model.Chunk;
import org.alfresco.contentlake.model.HxprDocument;
import org.alfresco.contentlake.model.HxprEmbedding;
import org.alfresco.contentlake.service.Chunker;
import org.alfresco.contentlake.service.EmbeddingService;
import org.alfresco.contentlake.service.chunking.SimpleChunkingService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background worker that converts content to text, chunks it, generates embeddings, and updates hxpr.
 *
 * <p>When semantic chunking is enabled ({@code ingestion.semantic.enabled=true}), uses the
 * {@link SimpleChunkingService} pipeline (noise reduction → content-type classification →
 * strategy-based chunking). Otherwise falls back to the simple character-based {@link Chunker}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransformationWorker {

    private static final String TARGET_MIME_TYPE = "text/plain";

    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain", "text/html", "text/xml", "text/csv",
            "text/markdown", "application/json", "application/xml",
            "application/javascript"
    );

    private final TransformationQueue queue;
    private final AlfrescoClient alfrescoClient;
    private final HxprDocumentApi documentApi;
    private final HxprService hxprService;
    private final TransformClient transformClient;
    private final Chunker chunker;
    private final EmbeddingService embeddingService;
    private final SimpleChunkingService chunkingService;
    private final IngestionProperties props;

    private ExecutorService executor;
    private volatile boolean running = true;

    @PostConstruct
    public void start() {
        int workerCount = props.getTransform().getWorkerThreads();
        executor = Executors.newFixedThreadPool(workerCount);

        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::processLoop);
        }

        log.info("Started {} transformation workers", workerCount);
    }

    @PreDestroy
    public void stop() {
        running = false;

        if (executor == null) {
            return;
        }

        executor.shutdownNow();
        try {
            boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
            if (!terminated) {
                log.warn("TransformationWorker executor did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("TransformationWorker shutdown interrupted", e);
        }
    }

    private void processLoop() {
        while (running) {
            try {
                TransformationTask task = queue.take();
                processTask(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in transformation worker", e);
            }
        }
    }

    private void processTask(TransformationTask task) {
        log.debug("Processing transformation for node: {}", task.getNodeId());

        try {
            String mimeType = task.getMimeType();
            String text = extractText(task.getNodeId(), mimeType);

            if (text == null || text.isBlank()) {
                log.warn("Transformation returned empty text for node: {} ({})", task.getNodeId(), mimeType);
                queue.markCompleted();
                return;
            }

            // Choose chunking approach based on configuration
            List<Chunk> chunks = chunkingService.chunk(text, task.getNodeId(), mimeType);

            log.info("Processing node {} - mime: {}, text length: {}, chunks: {}",
                    task.getNodeId(), mimeType, text.length(), chunks.size());

            if (chunks.isEmpty()) {
                log.info("No chunks generated for node: {}", task.getNodeId());
                queue.markCompleted();
                return;
            }

            List<EmbeddingService.ChunkWithEmbedding> embeddings = embeddingService.embedChunks(chunks);

            deleteEmbeddingsIfAny(task.getHxprDocumentId());

            List<HxprEmbedding> hxprEmbeddings = toHxprEmbeddings(embeddings);
            hxprService.updateEmbeddings(task.getHxprDocumentId(), hxprEmbeddings);

            updateFulltext(task.getHxprDocumentId(), text);

            queue.markCompleted();
            log.info("Completed transformation for node: {}, created {} embeddings",
                    task.getNodeId(), embeddings.size());

        } catch (Exception e) {
            log.error("Failed transformation for node: {}", task.getNodeId(), e);
            queue.markFailed();
        }
    }

    /**
     * Extracts plain text from a node.
     */
    private String extractText(String nodeId, String mimeType) throws IOException {
        if (isTextMimeType(mimeType)) {
            byte[] content = alfrescoClient.getContent(nodeId);
            log.debug("Using content directly for text mime type: {}", mimeType);
            return new String(content, StandardCharsets.UTF_8);
        }

        log.info("Requesting text transformation for {} ({})", nodeId, mimeType);

        Resource tempContent = alfrescoClient.downloadContentToTempFile(
                nodeId,
                nodeId + extensionForMimeType(mimeType)
        );

        try {
            return transformToText(tempContent, mimeType);
        } finally {
            deleteTempFileIfAny(tempContent);
        }
    }

    private void deleteEmbeddingsIfAny(String hxprDocumentId) {
        try {
            hxprService.deleteEmbeddings(hxprDocumentId);
        } catch (Exception e) {
            log.debug("No existing embeddings to delete for document: {}", hxprDocumentId);
        }
    }

    private List<HxprEmbedding> toHxprEmbeddings(List<EmbeddingService.ChunkWithEmbedding> embeddings) {
        List<HxprEmbedding> hxprEmbeddings = new ArrayList<>(embeddings.size());

        for (EmbeddingService.ChunkWithEmbedding cwe : embeddings) {
            HxprEmbedding embedding = new HxprEmbedding();
            embedding.setText(cwe.chunk().getText());
            embedding.setVector(cwe.embedding());
            embedding.setType(embeddingService.getModelName());
            embedding.setLocation(buildLocation(cwe.chunk().getIndex()));
            embedding.setChunkId(cwe.chunk().getId());
            hxprEmbeddings.add(embedding);
        }

        return hxprEmbeddings;
    }

    private HxprEmbedding.EmbeddingLocation buildLocation(int paragraphIndex) {
        HxprEmbedding.EmbeddingLocation location = new HxprEmbedding.EmbeddingLocation();
        HxprEmbedding.EmbeddingLocation.TextLocation textLoc = new HxprEmbedding.EmbeddingLocation.TextLocation();
        textLoc.setParagraph(paragraphIndex);
        location.setText(textLoc);
        return location;
    }

    private void updateFulltext(String hxprDocumentId, String text) {
        HxprDocument update = new HxprDocument();
        update.setSysFulltextBinary(text);
        documentApi.updateById(hxprDocumentId, update);
    }

    private boolean isTextMimeType(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        if (TEXT_MIME_TYPES.contains(mimeType)) {
            return true;
        }
        return mimeType.startsWith("text/") || mimeType.endsWith("+xml") || mimeType.endsWith("+json");
    }

    private String transformToText(Resource content, String sourceMimeType) {
        byte[] out = transformClient.transformSync(content, sourceMimeType, TARGET_MIME_TYPE);
        return out == null ? null : new String(out, StandardCharsets.UTF_8);
    }

    private void deleteTempFileIfAny(Resource resource) {
        if (resource instanceof FileSystemResource fsr) {
            try {
                Files.deleteIfExists(fsr.getFile().toPath());
            } catch (Exception e) {
                log.debug("Could not delete temp file {}", safePath(resource), e);
            }
        }
    }

    private String safePath(Resource resource) {
        try {
            if (resource instanceof FileSystemResource fsr) {
                return fsr.getFile().getAbsolutePath();
            }
        } catch (Exception ignore) {
        }
        return resource.getDescription();
    }

    private String extensionForMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        return switch (mimeType) {
            case "application/pdf" -> ".pdf";
            case "text/plain" -> ".txt";
            case "text/html" -> ".html";
            default -> "";
        };
    }
}
