package org.hyland.contentlake.rag.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dual-source path used by the standalone demo UI, which queries both repositories in one
 * request.
 *
 * <p>Both credentials must be present and valid or the filter falls through to the single-source
 * filters, which can only establish one of the two identities. A silent fall-through is the failure
 * mode to guard against: the caller stays authenticated, so nothing looks broken, but half their
 * documents disappear from the results.</p>
 */
class DualSourceAuthenticationFilterTest {

    private final MultiSourceAuthenticationProvider provider = mock(MultiSourceAuthenticationProvider.class);
    private final DualSourceAuthenticationFilter filter = new DualSourceAuthenticationFilter(provider);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void establishesBothIdentitiesAndHidesBothHeadersFromDownstreamFilters() throws Exception {
        when(provider.validateAlfrescoTicket("TICKET_ui-demo")).thenReturn("alice");
        when(provider.validateNuxeoCredentials("jdoe", "secret")).thenReturn("jdoe");

        MockHttpServletRequest request = dualRequest(basic("TICKET_ui-demo:"), basic("jdoe:secret"));
        String[] forwarded = runFilter(request);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isInstanceOf(DualSourceAuthentication.class);
        var dual = (DualSourceAuthentication) authentication;
        assertThat(dual.getAlfrescoUsername()).isEqualTo("alice");
        assertThat(dual.getNuxeoUsername()).isEqualTo("jdoe");
        assertThat(forwarded).containsExactly(null, null);
    }

    @Test
    void fallsThroughOnABareTicketSoOnlyOneEncodingIsAccepted() throws Exception {
        MockHttpServletRequest request = dualRequest(basic("TICKET_ui-demo"), basic("jdoe:secret"));

        runFilter(request);

        verify(provider, never()).validateAlfrescoTicket(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void fallsThroughWhenTheNuxeoHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rag/search/hybrid");
        request.addHeader("Authorization", basic("TICKET_ui-demo:"));

        runFilter(request);

        verify(provider, never()).validateAlfrescoTicket(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void fallsThroughWhenTheAuthorizationHeaderIsNotATicket() throws Exception {
        MockHttpServletRequest request = dualRequest(basic("admin:admin"), basic("jdoe:secret"));

        runFilter(request);

        verify(provider, never()).validateAlfrescoTicket(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void fallsThroughAndLeavesBothHeadersWhenTheNuxeoCredentialIsRejected() throws Exception {
        when(provider.validateAlfrescoTicket("TICKET_ui-demo")).thenReturn("alice");
        when(provider.validateNuxeoCredentials("jdoe", "wrong")).thenReturn(null);

        MockHttpServletRequest request = dualRequest(basic("TICKET_ui-demo:"), basic("jdoe:wrong"));
        String[] forwarded = runFilter(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(forwarded).containsExactly(basic("TICKET_ui-demo:"), basic("jdoe:wrong"));
    }

    /** Runs the filter and returns the two auth headers the downstream chain can still see. */
    private String[] runFilter(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] forwarded = new String[2];

        filter.doFilter(request, response, (req, res) -> {
            HttpServletRequest downstream = (HttpServletRequest) req;
            forwarded[0] = downstream.getHeader("Authorization");
            forwarded[1] = downstream.getHeader("X-Nuxeo-Authorization");
        });

        return forwarded;
    }

    private static MockHttpServletRequest dualRequest(String authorization, String nuxeoAuthorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rag/search/hybrid");
        request.addHeader("Authorization", authorization);
        request.addHeader("X-Nuxeo-Authorization", nuxeoAuthorization);
        return request;
    }

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
