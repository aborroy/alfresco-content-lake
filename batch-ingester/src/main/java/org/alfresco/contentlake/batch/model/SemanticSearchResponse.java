package org.alfresco.contentlake.batch.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response payload for the semantic search validation endpoint.
 *
 * <p>Each result contains the matched chunk text, source document metadata,
 * the cosine similarity score, and chunk-level metadata useful for
 * evaluating search quality before building the full RAG pipeline.</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SemanticSearchResponse {

    /** Original query text. */
    private String query;

    /** Embedding model used for the query vector. */
    private String model;

    /** Dimensionality of the query vector. */
    private int vectorDimension;

    /** Number of results returned. */
    private int resultCount;

    /** Total matching embeddings (approximate when trackTotalCount is false). */
    private long totalCount;

    /** Time taken for the search in milliseconds. */
    private long searchTimeMs;

    /** Ordered list of search hits. */
    private List<SearchHit> results;

    /**
     * A single search hit combining chunk content, source document info and metadata.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SearchHit {

        /** Rank position (1-based). */
        private int rank;

        /** Cosine similarity score (0.0 – 1.0). */
        private double score;

        /** The matched chunk text. */
        private String chunkText;

        /** Source document metadata. */
        private SourceDocument sourceDocument;

        /** Chunk positioning and strategy metadata. */
        private ChunkMetadata chunkMetadata;
    }

    /**
     * Source document identification and metadata retrieved from the parent document.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SourceDocument {

        /** HXPR internal document identifier. */
        private String documentId;

        /** Alfresco node identifier (stored in cin_id / sys_name). */
        private String nodeId;

        /** Document name from Alfresco. */
        private String name;

        /** Document path from Alfresco. */
        private String path;

        /** MIME type of the source document. */
        private String mimeType;
    }

    /**
     * Metadata about the chunk within the source document.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChunkMetadata {

        /** Embedding identifier within the HXPR index. */
        private String embeddingId;

        /** Embedding type / model identifier. */
        private String embeddingType;

        /** Page number (if available from location metadata). */
        private Integer page;

        /** Paragraph index (if available from location metadata). */
        private Integer paragraph;

        /** Character length of the chunk text. */
        private int chunkLength;
    }
}
