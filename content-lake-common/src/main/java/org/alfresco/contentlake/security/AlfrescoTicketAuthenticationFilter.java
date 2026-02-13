package org.alfresco.contentlake.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Filter that extracts Alfresco tickets from requests.
 * Checks both query parameters (?alf_ticket=...) and Authorization header.
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

        if (ticket == null) {
            ticket = extractTicketFromHeader(request);
        }

        if (ticket != null && ticket.startsWith("TICKET_")) {
            try {
                log.debug("Found Alfresco ticket in request");
                PreAuthenticatedAuthenticationToken authRequest =
                        new PreAuthenticatedAuthenticationToken(ticket, ticket);
                Authentication authentication = authenticationManager.authenticate(authRequest);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                log.debug("Ticket authentication failed: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extracts ticket from Authorization header if it's in Basic auth format
     * with just a ticket (no colon separator).
     */
    private String extractTicketFromHeader(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String base64Credentials = authHeader.substring(6);
                byte[] decodedBytes = Base64.getDecoder().decode(base64Credentials);
                String credentials = new String(decodedBytes, StandardCharsets.UTF_8);
                if (!credentials.contains(":") && credentials.startsWith("TICKET_")) {
                    return credentials;
                }
            } catch (Exception e) {
                log.debug("Failed to extract ticket from header: {}", e.getMessage());
            }
        }

        return null;
    }
}