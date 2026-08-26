package org.hyland.contentlake.rag.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Operational status of the RAG service and its dependencies, returned by
 * {@code GET /api/status}. Distinct from {@code /actuator/health}: this is a single
 * human-readable operational snapshot including indexed document counts.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatusResponse {

    /** {@code "UP"} when hxpr answered a probe query, otherwise {@code "DOWN"}. */
    private String hxprStatus;

    /** Total indexed documents across all sources (sum of per-source counts). */
    private long totalDocuments;

    /** Indexed document count keyed by {@code cin_sourceId} ({@code "<type>:<id>"}). */
    private Map<String, Long> sourceCounts;

    /** Reachability of the embedding/LLM model runner. */
    private ModelRunnerStatus embeddingModel;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelRunnerStatus {
        /** {@code "UP"} when the model runner answered, otherwise {@code "DOWN"}. */
        private String status;
        /** The probed URL. */
        private String url;
    }
}
