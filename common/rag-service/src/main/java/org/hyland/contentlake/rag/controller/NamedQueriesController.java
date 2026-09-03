package org.hyland.contentlake.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.NamedQueryService;
import org.hyland.contentlake.rag.config.RagProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing the hxpr named-query definitions registered server-side, so clients can
 * offer them as saved-search filters.
 *
 * <p>Search requests already accept a {@code namedQuery} name (resolved to an HXQL filter server-side);
 * this endpoint lets a client discover the available names to populate a selector. Requires
 * authentication like the other {@code /api/rag/**} endpoints.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/named-queries")
@RequiredArgsConstructor
public class NamedQueriesController {

    private final NamedQueryService namedQueryService;
    private final RagProperties ragProperties;

    /**
     * Lists the names of the named-query definitions registered in hxpr.
     *
     * @return the registered named-query names, empty when none are registered or when discovery is
     *         switched off via {@code rag.namedQuery.discovery.enabled}
     */
    @GetMapping
    public List<String> list() {
        if (!ragProperties.getNamedQuery().getDiscovery().isEnabled()) {
            log.debug("Named-query discovery is disabled; returning an empty list");
            return List.of();
        }
        List<String> names = namedQueryService.list();
        log.debug("Listed {} named queries", names.size());
        return names;
    }
}
