package org.hyland.contentlake.rag.config;

import org.hyland.contentlake.rag.service.NoOpRerankService;
import org.hyland.contentlake.rag.service.RerankService;
import org.hyland.contentlake.rag.service.TeiCrossEncoderRerankService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers exactly one {@link RerankService}:
 * <ul>
 *   <li>{@link TeiCrossEncoderRerankService} when {@code rag.reranker.url} is set to a non-blank value</li>
 *   <li>{@link NoOpRerankService} otherwise (keeps retrieval ordering unchanged)</li>
 * </ul>
 *
 * <p>Beans are declared in this class so that {@code @ConditionalOnMissingBean} is
 * evaluated after the property-conditional bean, avoiding class-scan ordering issues.
 * {@code @ConditionalOnExpression} is used instead of {@code @ConditionalOnProperty}
 * because the latter treats an empty string as "present and not false".</p>
 */
@Configuration
public class RerankServiceConfig {

    @Bean
    @ConditionalOnExpression("'${rag.reranker.url:}' != ''")
    public RerankService teiCrossEncoderRerankService(RagProperties ragProperties) {
        return new TeiCrossEncoderRerankService(ragProperties);
    }

    @Bean
    @ConditionalOnMissingBean(RerankService.class)
    public RerankService noOpRerankService() {
        return new NoOpRerankService();
    }
}
