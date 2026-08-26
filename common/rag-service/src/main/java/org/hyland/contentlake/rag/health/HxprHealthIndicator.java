package org.hyland.contentlake.rag.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes hxpr connectivity to {@code /actuator/health} under the {@code hxpr} component.
 *
 * <p>Probes hxpr with a minimal limit-1 HXQL query. {@code SELECT 1} is not valid HXQL, so the probe
 * uses the same {@code SELECT * FROM SysContent} form the search services rely on.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HxprHealthIndicator implements HealthIndicator {

    static final String PROBE_QUERY = "SELECT * FROM SysContent";

    private final HxprService hxprService;

    @Override
    public Health health() {
        try {
            hxprService.query(PROBE_QUERY, 1, 0);
            return Health.up().withDetail("probe", PROBE_QUERY).build();
        } catch (Exception e) {
            log.debug("hxpr health probe failed: {}", e.getMessage());
            return Health.down(e).build();
        }
    }
}
