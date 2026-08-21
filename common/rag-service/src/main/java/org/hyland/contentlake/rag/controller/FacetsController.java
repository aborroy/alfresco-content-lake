package org.hyland.contentlake.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.model.FacetsRequest;
import org.hyland.contentlake.rag.model.FacetsResponse;
import org.hyland.contentlake.rag.service.FacetsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for faceted search: discovers the indexed values of a property (with counts)
 * so callers can build informed HXQL filters.
 *
 * <p>Requires authentication; buckets are scoped to the caller's document permissions.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/search/facets")
@RequiredArgsConstructor
public class FacetsController {

    private final FacetsService facetsService;

    /**
     * Aggregates the top values of a property.
     *
     * @param request the property to facet on plus optional filter/sourceType/searchTerm/topN
     * @return the aggregated buckets, or 400 when no property is supplied
     */
    @PostMapping
    public ResponseEntity<FacetsResponse> facets(@RequestBody FacetsRequest request) {
        if (request.getProperty() == null || request.getProperty().isBlank()) {
            return ResponseEntity.badRequest().body(
                    FacetsResponse.builder().property("").build());
        }

        log.debug("Facets request: property=\"{}\", topN={}", request.getProperty(), request.getTopN());

        return ResponseEntity.ok(facetsService.facets(request));
    }
}
