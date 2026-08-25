package org.hyland.alfresco.contentlake.batch.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.service.GraphProvisioningService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs GraphRAG provisioning (graphDB + base ontology + ontology route) once at startup.
 *
 * <p>Present only when {@code hxpr.graph.enabled=true}, matching the guard on
 * {@link org.hyland.contentlake.config.HxprGraphConfig} so its dependency
 * {@link GraphProvisioningService} exists.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hxpr.graph.enabled", havingValue = "true")
public class GraphProvisioningRunner implements ApplicationRunner {

    private final GraphProvisioningService graphProvisioningService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Graph provisioning enabled; ensuring hxpr graphDB and ontology exist.");
        graphProvisioningService.ensureGraphProvisioned();
    }
}
