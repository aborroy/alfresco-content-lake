package org.hyland.contentlake.rag.config;

import org.hyland.contentlake.rag.service.DiversitySelector;
import org.hyland.contentlake.rag.service.MmrSelector;
import org.hyland.contentlake.rag.service.NoOpDiversitySelector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers exactly one {@link DiversitySelector}:
 * <ul>
 *   <li>{@link MmrSelector} when {@code rag.mmr.enabled} is true</li>
 *   <li>{@link NoOpDiversitySelector} otherwise (keeps retrieval ordering unchanged)</li>
 * </ul>
 *
 * <p>Beans are declared in this class so that {@code @ConditionalOnMissingBean} is evaluated after
 * the property-conditional bean, avoiding class-scan ordering issues (mirrors
 * {@link RerankServiceConfig}).</p>
 */
@Configuration
public class DiversityConfig {

    @Bean
    @ConditionalOnExpression("${rag.mmr.enabled:false}")
    public DiversitySelector mmrSelector(RagProperties ragProperties) {
        return new MmrSelector(ragProperties);
    }

    @Bean
    @ConditionalOnMissingBean(DiversitySelector.class)
    public DiversitySelector noOpDiversitySelector() {
        return new NoOpDiversitySelector();
    }
}
