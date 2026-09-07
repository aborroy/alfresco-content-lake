package org.hyland.alfresco.contentlake.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.security.AlfrescoTicketHeader;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

/**
 * Filter that extracts Alfresco tickets from requests.
 * Checks both query parameters (?alf_ticket=...) and Authorization header.
 *
 * The one accepted header encoding is defined by {@link AlfrescoTicketHeader}, shared with
 * rag-service so the two services cannot drift apart on what a ticket header looks like.
 *
 * When a ticket is extracted from the Authorization header and authentication
 * succeeds, the header is stripped from the request before continuing the
 * filter chain. This prevents Spring's BasicAuthenticationFilter from
 * re-processing the header, which would fail: a ticket is not a username, so
 * authenticating it as one yields a 401 + WWW-Authenticate response.
 */
@Slf4j
public class AlfrescoTicketAuthenticationFilter extends OncePerRequestFilter {

    private final AuthenticationManager authenticationManager;

    public AlfrescoTicketAuthenticationFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String ticket = request.getParameter("alf_ticket");
        boolean ticketFromHeader = false;

        if (ticket == null) {
            ticket = extractTicketFromHeader(request);
            ticketFromHeader = ticket != null;
        }

        boolean authenticated = false;
        if (ticket != null && ticket.startsWith(AlfrescoTicketHeader.TICKET_PREFIX)) {
            try {
                log.debug("Found Alfresco ticket in request");
                PreAuthenticatedAuthenticationToken authRequest =
                        new PreAuthenticatedAuthenticationToken(ticket, ticket);
                Authentication authentication = authenticationManager.authenticate(authRequest);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                authenticated = true;
            } catch (Exception e) {
                log.debug("Ticket authentication failed: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        // Strip the Authorization header so BasicAuthenticationFilter does not
        // attempt to parse the ticket as user:password credentials
        if (authenticated && ticketFromHeader) {
            filterChain.doFilter(new AuthorizationHeaderStrippingRequest(request), response);
        } else {
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Extracts the ticket from a Basic Authorization header, in the encoding
     * {@link AlfrescoTicketHeader} accepts.
     */
    private String extractTicketFromHeader(HttpServletRequest request) {
        return AlfrescoTicketHeader.extractTicket(request.getHeader("Authorization"));
    }

    /**
     * Request wrapper that hides the Authorization header from downstream filters.
     */
    private static class AuthorizationHeaderStrippingRequest extends HttpServletRequestWrapper {

        private static final String AUTHORIZATION = "authorization";

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
