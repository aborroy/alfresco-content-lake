package org.hyland.contentlake.rag.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class RagAuthenticationEntryPointTest {

    private final RagAuthenticationEntryPoint entryPoint = new RagAuthenticationEntryPoint();

    @Test
    void omitsBrowserChallengeForRagApiRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rag/chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("bad creds"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("Authentication required");
    }

    @Test
    void keepsBasicChallengeOutsideRagApi() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/other");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("bad creds"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"RAG Service\"");
    }
}
