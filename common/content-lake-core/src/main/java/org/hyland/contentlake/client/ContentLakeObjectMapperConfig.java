package org.hyland.contentlake.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the classic Jackson 2 {@link ObjectMapper} the core HXPR clients are written against.
 *
 * <p>Spring Boot 4's {@code JacksonAutoConfiguration} targets Jackson 3 ({@code tools.jackson}) via
 * {@code spring-boot-starter-jackson} and no longer registers a
 * {@code com.fasterxml.jackson.databind.ObjectMapper} bean, so core supplies one explicitly. Guarded
 * with {@link ConditionalOnMissingBean} so an application module defining its own wins.</p>
 */
@Configuration
public class ContentLakeObjectMapperConfig {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper contentLakeObjectMapper() {
        return new ObjectMapper();
    }
}
