package org.hyland.alfresco.contentlake.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The chain denies by default: only the container probes are public.
 *
 * <p>A permitted path that has no handler in the web slice answers 404, which is the signal that it
 * passed authorization. A blocked path answers 401 before routing.</p>
 */
@WebMvcTest
@Import(SecurityConfig.class)
@TestPropertySource(properties = "content.service.url=http://localhost:8080")
class SecurityConfigDefaultDenyTest {

    @Autowired
    private MockMvc mockMvc;

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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
