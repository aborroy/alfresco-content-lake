package org.hyland.contentlake.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

/**
 * Wires HXPR model provisioning support.
 *
 * <p>The provisioner is used by application modules (batch-ingester, etc.)
 * to ensure required HXPR model fragments exist at bootstrap.
 */
@Configuration
public class HxprModelProvisionerConfig {

    /**
     * Jackson 2 {@link ObjectMapper} used by the model provisioner (and other core clients).
     *
     * <p>Spring Boot 4's {@code JacksonAutoConfiguration} now targets Jackson 3
     * ({@code tools.jackson}) via {@code spring-boot-starter-jackson} and no longer registers a
     * classic {@code com.fasterxml.jackson.databind.ObjectMapper} bean. The HXPR clients here are
     * written against Jackson 2, so core provides one explicitly. Guarded with
     * {@link ConditionalOnMissingBean} so an application module that already defines its own
     * Jackson 2 {@code ObjectMapper} wins.</p>
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper contentLakeObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public HxprModelProvisioner hxprModelProvisioner(RestClient hxprRestClient,
                                                     ResourceLoader resourceLoader,
                                                     ObjectMapper objectMapper) {
        return new HxprModelProvisioner(hxprRestClient, resourceLoader, objectMapper);
    }
}
