package org.hyland.contentlake.rag.config;

import org.hyland.contentlake.rag.service.NoOpRetrievalGrader;
import org.hyland.contentlake.rag.service.RetrievalGrader;
import org.hyland.contentlake.rag.service.ScoreThresholdRetrievalGrader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers exactly one {@link RetrievalGrader}:
 * <ul>
 *   <li>{@link ScoreThresholdRetrievalGrader} when {@code rag.retrieval-grading.enabled} is true</li>
 *   <li>{@link NoOpRetrievalGrader} otherwise (every non-empty context reaches the LLM)</li>
 * </ul>
 *
 * <p>Beans are declared in this class so that {@code @ConditionalOnMissingBean} is evaluated after the
 * property-conditional bean, avoiding class-scan ordering issues (mirrors {@link DiversityConfig} and
 * {@link RerankServiceConfig}).</p>
 */
@Configuration
public class RetrievalGradingConfig {

    @Bean
    @ConditionalOnExpression("${rag.retrieval-grading.enabled:false}")
    public RetrievalGrader scoreThresholdRetrievalGrader(RagProperties ragProperties) {
        return new ScoreThresholdRetrievalGrader(ragProperties);
    }

    @Bean
    @ConditionalOnMissingBean(RetrievalGrader.class)
    public RetrievalGrader noOpRetrievalGrader() {
        return new NoOpRetrievalGrader();
    }
}
