package org.alfresco.contentlake.batch.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;

/**
 * Authentication provider that validates Alfresco tickets.
 * Validates tickets by making a test API call to Alfresco.
 */
@Slf4j
@Component
public class AlfrescoTicketAuthenticationProvider implements AuthenticationProvider {

    private final String alfrescoUrl;
    private final RestTemplate restTemplate;

    public AlfrescoTicketAuthenticationProvider(@Value("${content.service.url}") String alfrescoUrl) {
        this.alfrescoUrl = alfrescoUrl;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String ticket = (String) authentication.getPrincipal();

        log.debug("Authenticating with ticket: {}...", ticket.substring(0, Math.min(20, ticket.length())));

        if (validateAlfrescoTicket(ticket)) {
            log.info("Successfully authenticated with ticket");
            return new PreAuthenticatedAuthenticationToken(
                    ticket,
                    ticket,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
            );
        }

        log.warn("Authentication failed with invalid ticket");
        throw new BadCredentialsException("Invalid Alfresco ticket");
    }

    private boolean validateAlfrescoTicket(String ticket) {
        try {
            String url = alfrescoUrl + "/alfresco/api/-default-/public/alfresco/versions/1/-me-?alf_ticket=" + ticket;

            HttpHeaders headers = new HttpHeaders();
            HttpEntity<?> request = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    Map.class
            );

            return response.getStatusCode() == HttpStatus.OK;

        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            log.debug("Alfresco rejected ticket");
            return false;
        } catch (Exception e) {
            log.error("Error validating ticket with Alfresco: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PreAuthenticatedAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
