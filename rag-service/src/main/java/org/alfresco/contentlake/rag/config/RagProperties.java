package org.alfresco.contentlake.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for RAG behaviour.
 *
 * <p>Bound from {@code rag.*} in {@code application.yml}.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** Default number of chunks to retrieve for context. */
    private int defaultTopK = 5;

    /** Default minimum similarity score threshold. */
    private double defaultMinScore = 0.5;

    /** Maximum character length of the assembled context sent to the LLM. */
    private int maxContextLength = 4000;

    /** Default system prompt for the LLM. */
    private String defaultSystemPrompt = """
            You are a helpful assistant that answers questions based on the provided document context.
            Use ONLY the information from the context below to answer the question.
            If the context does not contain enough information to answer, say so clearly.
            Always cite the source document name when referencing specific information.""";
}
