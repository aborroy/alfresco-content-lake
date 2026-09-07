package org.hyland.alfresco.contentlake.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ticket encodings this ingester accepts on {@code /api/content-lake}.
 *
 * <p>The ACA extension is the only client of these endpoints, and a mismatch here is invisible in the
 * deployment E2E suite, which exercises rag-service rather than the ingester. These assertions are
 * the actual proof that the Content Lake indicators in ACA can still authenticate.</p>
 */
class AlfrescoTicketAuthenticationFilterTest {

    private final RecordingAuthenticationManager authenticationManager = new RecordingAuthenticationManager();
    private final AlfrescoTicketAuthenticationFilter filter =
            new AlfrescoTicketAuthenticationFilter(authenticationManager);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ticketWithEmptyPasswordAuthenticatesAndIsHiddenFromBasicAuthentication() throws Exception {
        MockHttpServletRequest request = requestWithAuthorization(basic("TICKET_aca-demo:"));

        String forwardedAuthorization = runFilter(request);

        assertThat(authenticationManager.presentedPrincipals).containsExactly("TICKET_aca-demo");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(forwardedAuthorization).isNull();
    }

    @Test
    void bareTicketIsNoLongerRecognisedAsATicket() throws Exception {
        MockHttpServletRequest request = requestWithAuthorization(basic("TICKET_aca-demo"));

        String forwardedAuthorization = runFilter(request);

        // Left for BasicAuthenticationFilter, which cannot authenticate a ticket as a username and
        // answers 401. One encoding is accepted, and this is not it.
        assertThat(authenticationManager.presentedPrincipals).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(forwardedAuthorization).isEqualTo(basic("TICKET_aca-demo"));
    }

    @Test
    void realBasicCredentialsAreLeftForSpringBasicAuthentication() throws Exception {
        MockHttpServletRequest request = requestWithAuthorization(basic("admin:admin"));

        String forwardedAuthorization = runFilter(request);

        assertThat(authenticationManager.presentedPrincipals).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(forwardedAuthorization).isEqualTo(basic("admin:admin"));
    }

    @Test
    void ticketQueryParameterTakesPrecedenceAndLeavesTheHeaderAlone() throws Exception {
        MockHttpServletRequest request = requestWithAuthorization(basic("admin:admin"));
        request.setParameter("alf_ticket", "TICKET_query-demo");

        String forwardedAuthorization = runFilter(request);

        assertThat(authenticationManager.presentedPrincipals).containsExactly("TICKET_query-demo");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        // The ticket did not come from the header, so the header is not the filter's to remove.
        assertThat(forwardedAuthorization).isEqualTo(basic("admin:admin"));
    }

    /** Runs the filter and returns the Authorization header the downstream chain can see. */
    private String runFilter(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] forwarded = new String[1];

        filter.doFilter(request, response, (req, res) ->
                forwarded[0] = ((HttpServletRequest) req).getHeader("Authorization"));

        return forwarded[0];
    }

    private static MockHttpServletRequest requestWithAuthorization(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/content-lake/nodes/abc/status");
        request.addHeader("Authorization", value);
        return request;
    }

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /** Accepts anything and records what it was asked to authenticate. */
    private static class RecordingAuthenticationManager implements AuthenticationManager {

        private final List<String> presentedPrincipals = new ArrayList<>();

        @Override
        public Authentication authenticate(Authentication authentication) {
            presentedPrincipals.add(authentication.getName());
            return new TestingAuthenticationToken(authentication.getName(), null, "ROLE_USER");
        }
    }
}
