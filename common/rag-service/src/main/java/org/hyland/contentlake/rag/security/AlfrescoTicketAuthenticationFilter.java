package org.hyland.contentlake.rag.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.security.AlfrescoTicketHeader;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Authenticates Alfresco UI tickets before Spring's Basic auth filter.
 *
 * <p>The UI sends Alfresco tickets as {@code Authorization: Basic base64(ticket:)}.
 * While this resembles Basic auth, it is not a normal username/password pair, so
 * the request is normalized here and the Authorization header is stripped after
 * successful authentication to avoid double-processing later in the chain.</p>
 *
 * <p>The one accepted header encoding is defined by {@link AlfrescoTicketHeader}, shared with the
 * Alfresco batch ingester so the two services cannot drift apart on what a ticket header looks
 * like.</p>
 */
@Slf4j
public class AlfrescoTicketAuthenticationFilter extends OncePerRequestFilter {

    private static final String ALFRESCO_TICKET_PARAM = "alf_ticket";
    private static final String AUTHORIZATION = "authorization";
    private static final String NUXEO_AUTHORIZATION_HEADER = "X-Nuxeo-Authorization";

    private final AuthenticationManager authenticationManager;

    public AlfrescoTicketAuthenticationFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String ticket = null;
        boolean ticketFromAuthorizationHeader = false;

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            // Dual-auth requests carry a dedicated Nuxeo header and should be handled
            // by DualSourceAuthenticationFilter instead of short-circuiting here.
            if (request.getHeader(NUXEO_AUTHORIZATION_HEADER) != null) {
                filterChain.doFilter(request, response);
                return;
            }

            ticket = request.getParameter(ALFRESCO_TICKET_PARAM);
            if (ticket == null || ticket.isBlank()) {
                ticket = extractTicketFromAuthorizationHeader(request);
                ticketFromAuthorizationHeader = ticket != null;
            }

            if (ticket != null && ticket.startsWith(AlfrescoTicketHeader.TICKET_PREFIX)) {
                try {
                    Authentication authentication = authenticationManager.authenticate(
                            new UsernamePasswordAuthenticationToken(ticket, "")
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (Exception e) {
                    log.debug("Alfresco ticket authentication failed: {}", e.getMessage());
                    SecurityContextHolder.clearContext();
                    ticketFromAuthorizationHeader = false;
                }
            }
        }

        if (ticketFromAuthorizationHeader && SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(new AuthorizationHeaderStrippingRequest(request), response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String extractTicketFromAuthorizationHeader(HttpServletRequest request) {
        return AlfrescoTicketHeader.extractTicket(request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    private static class AuthorizationHeaderStrippingRequest extends HttpServletRequestWrapper {

        AuthorizationHeaderStrippingRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            if (AUTHORIZATION.equalsIgnoreCase(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (AUTHORIZATION.equalsIgnoreCase(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original.hasMoreElements()) {
                String name = original.nextElement();
                if (!AUTHORIZATION.equalsIgnoreCase(name)) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }
    }
}
