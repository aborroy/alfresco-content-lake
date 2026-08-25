package org.hyland.contentlake.service;

import org.hyland.contentlake.client.HxprGraphService;
import org.hyland.contentlake.config.HxprProperties;
import org.hyland.contentlake.model.graph.GraphDbResponse;
import org.hyland.contentlake.model.graph.OntologyResponse;
import org.hyland.contentlake.model.graph.OntologyRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provisioning is idempotent: it creates the graphDB/ontology only when absent, but always
 * (re)asserts the ontology route (the PUT replaces the list, so it is safe to repeat).
 */
@ExtendWith(MockitoExtension.class)
class GraphProvisioningServiceTest {

    @Mock
    private HxprGraphService graphService;

    private final ResourceLoader resourceLoader = new DefaultResourceLoader();
    private HxprProperties properties;
    private GraphProvisioningService service;

    @BeforeEach
    void setUp() {
        properties = new HxprProperties();
        service = new GraphProvisioningService(graphService, properties, resourceLoader);
    }

    @Test
    void allAbsent_createsGraphDbWithV2_uploadsOntology_andRoutes() {
        when(graphService.findGraphDbByName("content-lake")).thenReturn(Optional.empty());
        when(graphService.createGraphDb("content-lake", "v2")).thenReturn(graphDb("gdb-1"));
        when(graphService.findOntologyByName("content-lake-base")).thenReturn(Optional.empty());
        when(graphService.uploadOntology(eq("content-lake-base"), anyString(), any()))
                .thenReturn(ontology("ont-1"));

        service.ensureGraphProvisioned();

        verify(graphService).createGraphDb("content-lake", "v2");
        verify(graphService).uploadOntology(eq("content-lake-base"), anyString(), any());
        verify(graphService).setOntologyRoutes(eq("gdb-1"), routesWithOntology("ont-1"));
        // The configured-id lookup must not run when no id is configured.
        verify(graphService, never()).findGraphDbById(anyString());
        // The resolved id is written back for downstream consumers.
        assertThat(properties.getGraph().getGraphdbId()).isEqualTo("gdb-1");
    }

    @Test
    void allPresent_makesNoCreateOrUploadCalls_butStillSetsRoute() {
        when(graphService.findGraphDbByName("content-lake")).thenReturn(Optional.of(graphDb("gdb-9")));
        when(graphService.findOntologyByName("content-lake-base")).thenReturn(Optional.of(ontology("ont-9")));

        service.ensureGraphProvisioned();

        verify(graphService, never()).createGraphDb(anyString(), anyString());
        verify(graphService, never()).uploadOntology(anyString(), anyString(), any());
        verify(graphService).setOntologyRoutes(eq("gdb-9"), routesWithOntology("ont-9"));
    }

    @Test
    void configuredGraphDbId_isVerifiedAndUsed_withoutNameLookupOrCreate() {
        properties.getGraph().setGraphdbId("gdb-cfg");
        when(graphService.findGraphDbById("gdb-cfg")).thenReturn(Optional.of(graphDb("gdb-cfg")));
        when(graphService.findOntologyByName("content-lake-base")).thenReturn(Optional.of(ontology("ont-2")));

        service.ensureGraphProvisioned();

        verify(graphService, never()).findGraphDbByName(anyString());
        verify(graphService, never()).createGraphDb(anyString(), anyString());
        verify(graphService).setOntologyRoutes(eq("gdb-cfg"), routesWithOntology("ont-2"));
    }

    // -- helpers -------------------------------------------------------------

    private static GraphDbResponse graphDb(String id) {
        GraphDbResponse db = new GraphDbResponse();
        db.setGraphDBId(id);
        db.setGraphDBName("content-lake");
        db.setVersion("v2");
        return db;
    }

    private static OntologyResponse ontology(String id) {
        OntologyResponse o = new OntologyResponse();
        o.setOntologyId(id);
        o.setOntologyName("content-lake-base");
        return o;
    }

    /** Mockito matcher: a single-route list whose one route references the given ontology id. */
    private static List<OntologyRoute> routesWithOntology(String ontologyId) {
        return org.mockito.ArgumentMatchers.argThat(routes ->
                routes != null && routes.size() == 1
                        && ontologyId.equals(routes.get(0).getOntologyId())
                        && routes.get(0).getCondition() != null
                        && !routes.get(0).getCondition().isBlank());
    }
}
