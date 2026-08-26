package org.hyland.contentlake.rag.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Contributes embedding/LLM model-runner reachability to {@code /actuator/health} under the
 * {@code modelRunner} component.
 *
 * <p>Issues a GET to the OpenAI-compatible {@code /models} endpoint. {@code spring.ai.openai.base-url}
 * already includes the {@code /v1} segment (see {@code application.yml}), so {@code /models} resolves
 * to {@code .../v1/models}.</p>
 */
@Slf4j
@Component
public class ModelRunnerHealthIndicator implements HealthIndicator {

    private final String modelsUrl;
    private final RestClient restClient;

    public ModelRunnerHealthIndicator(@Value("${spring.ai.openai.base-url}") String baseUrl) {
        this.modelsUrl = baseUrl + "/models";
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public Health health() {
        try {
            restClient.get().uri("/models").retrieve().toBodilessEntity();
            return Health.up().withDetail("url", modelsUrl).build();
        } catch (Exception e) {
            log.debug("Model runner health probe failed: {}", e.getMessage());
            return Health.down(e).withDetail("url", modelsUrl).build();
        }
    }
}
