package org.hyland.contentlake.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Reads the authenticated principal from the Spring Security context.
 *
 * <p>Every caller of {@link #getCurrentUsername()} uses the result to decide what the caller may read,
 * so there is no safe value to return when there is no caller. It therefore throws rather than
 * substituting a placeholder principal: a placeholder would resolve to a set of authorities and produce
 * a permission filter, which is a decision made on behalf of nobody.</p>
 */
@Slf4j
@Service
public class SecurityContextService {

    /**
     * The authenticated username.
     *
     * <p>An {@link AnonymousAuthenticationToken} counts as no principal even though it reports itself
     * as authenticated, and so does a principal with a blank name.</p>
     *
     * @return the authenticated username, never null or blank
     * @throws AuthenticationCredentialsNotFoundException when there is no authenticated principal. It is
     *         an {@code AuthenticationException}, so Spring Security's exception translation turns it
     *         into a 401 through the configured entry point rather than a 500.
     */
    public String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            log.warn("Rejecting a request with no authenticated principal");
            throw new AuthenticationCredentialsNotFoundException(
                    "No authenticated principal is present; the request cannot be scoped to a caller");
        }

        String username = authentication.getName();
        if (username == null || username.isBlank()) {
            log.warn("Rejecting a request whose authenticated principal has a blank name");
            throw new AuthenticationCredentialsNotFoundException(
                    "The authenticated principal has no name; the request cannot be scoped to a caller");
        }

        return username;
    }
}
