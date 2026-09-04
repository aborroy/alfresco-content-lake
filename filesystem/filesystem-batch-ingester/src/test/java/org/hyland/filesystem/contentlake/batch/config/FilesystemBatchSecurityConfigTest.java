package org.hyland.filesystem.contentlake.batch.config;

import org.hyland.filesystem.contentlake.batch.controller.SyncController;
import org.hyland.filesystem.contentlake.batch.model.IngestionJob;
import org.hyland.filesystem.contentlake.batch.service.FileSystemBatchIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sync API is reachable with the configured credential and closed to everyone else, and the
 * container probes stay public.
 */
@WebMvcTest
@Import({FilesystemBatchSecurityConfig.class, SyncController.class})
@TestPropertySource(properties = {
        "filesystem.batch.security.username=sync-user",
        "filesystem.batch.security.password=sync-secret"
})
class FilesystemBatchSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileSystemBatchIngestionService batchIngestionService;

    @Test
    void syncTrigger_isRejectedWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/sync/configured"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void syncTrigger_isRejectedWithTheWrongPassword() throws Exception {
        mockMvc.perform(post("/api/sync/configured").with(httpBasic("sync-user", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void syncTrigger_isAcceptedWithTheConfiguredCredential() throws Exception {
        when(batchIngestionService.startConfiguredSync()).thenReturn(new IngestionJob("job-1"));

        mockMvc.perform(post("/api/sync/configured").with(httpBasic("sync-user", "sync-secret")))
                .andExpect(status().isOk());
    }

    @Test
    void unmappedPath_isDeniedRatherThanPermitted() throws Exception {
        mockMvc.perform(get("/some/unmapped/path"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sensitiveActuatorEndpoint_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthProbe_passesTheChainWithoutCredentials() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }

    @Test
    void infoProbe_passesTheChainWithoutCredentials() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isNotFound());
    }

    /**
     * No credential means no service. Falling back to a default would publish a working credential
     * for an endpoint that triggers a full re-ingest.
     */
    @Test
    void missingCredentials_failStartup() {
        FilesystemBatchSecurityConfig config = new FilesystemBatchSecurityConfig();

        assertThatThrownBy(() -> config.userDetailsService(new FilesystemBatchProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filesystem.batch.security.username");

        FilesystemBatchProperties passwordMissing = new FilesystemBatchProperties();
        passwordMissing.getSecurity().setUsername("sync-user");

        assertThatThrownBy(() -> config.userDetailsService(passwordMissing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filesystem.batch.security.password");
    }

    @Test
    void configuredCredentials_buildTheAccount() {
        FilesystemBatchProperties props = new FilesystemBatchProperties();
        props.getSecurity().setUsername("sync-user");
        props.getSecurity().setPassword("sync-secret");

        assertThat(new FilesystemBatchSecurityConfig().userDetailsService(props)
                .loadUserByUsername("sync-user").getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_SYNC_ADMIN");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
