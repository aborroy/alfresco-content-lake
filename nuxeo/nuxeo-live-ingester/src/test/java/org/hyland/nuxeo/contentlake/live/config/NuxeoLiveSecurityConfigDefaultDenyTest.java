package org.hyland.nuxeo.contentlake.live.config;

import org.hyland.nuxeo.contentlake.config.NuxeoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The chain denies by default: only the container probes are public.
 *
 * <p>This service exposes no REST API of its own, which is exactly why the assertion matters: an
 * endpoint added later is authenticated without anyone remembering to say so.</p>
 */
@WebMvcTest
@Import({NuxeoLiveSecurityConfig.class, NuxeoLiveSecurityConfigDefaultDenyTest.TestConfig.class})
class NuxeoLiveSecurityConfigDefaultDenyTest {

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

    @TestConfiguration
    static class TestConfig {

        @Bean
        NuxeoProperties nuxeoProperties() {
            NuxeoProperties props = new NuxeoProperties();
            props.setBaseUrl("http://127.0.0.1:65535/nuxeo");
            props.setUsername("Administrator");
            props.setPassword("Administrator");
            return props;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
