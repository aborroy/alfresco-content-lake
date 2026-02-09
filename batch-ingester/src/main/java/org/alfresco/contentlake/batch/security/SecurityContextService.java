package org.alfresco.contentlake.batch.security;

import lombok.extern.slf4j.Slf4j;
import org.alfresco.contentlake.security.AlfrescoCredentials;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Service to extract authenticated user credentials from the Spring Security context.
 * Provides credentials that can be used for Alfresco API calls on behalf of the authenticated user.
 */
@Slf4j
@Service
public class SecurityContextService {

    /**
     * Extracts the authenticated user's credentials from the security context.
     *
     * @return Alfresco credentials (either username/password or ticket)
     * @throws IllegalStateException if no authenticated user is found
     */
    public AlfrescoCredentials getCurrentUserCredentials() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user found");
        }

        if (authentication instanceof PreAuthenticatedAuthenticationToken) {
            String ticket = (String) authentication.getCredentials();
            log.debug("Extracted ticket credentials from security context");
            return new AlfrescoCredentials(ticket);
        }

        String username = authentication.getName();
        String password = (String) authentication.getCredentials();
        log.debug("Extracted username/password credentials for user: {}", username);
        return new AlfrescoCredentials(username, password);
    }

    /**
     * Gets the authenticated username.
     *
     * @return username or ticket identifier
     */
    public String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }

        return authentication.getName();
    }
}
