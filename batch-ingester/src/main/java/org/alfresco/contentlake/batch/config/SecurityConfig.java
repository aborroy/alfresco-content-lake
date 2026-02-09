package org.alfresco.contentlake.batch.config;

import org.alfresco.contentlake.batch.security.AlfrescoAuthenticationProvider;
import org.alfresco.contentlake.batch.security.AlfrescoTicketAuthenticationFilter;
import org.alfresco.contentlake.batch.security.AlfrescoTicketAuthenticationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Spring Security configuration for the batch ingester REST API.
 *
 * <p>Secures all API endpoints with Alfresco authentication.
 * Supports both username/password (Basic Auth) and Alfresco ticket authentication.
 * Operations are performed as the authenticated user with their Alfresco permissions.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AlfrescoAuthenticationProvider alfrescoAuthProvider;
    private final AlfrescoTicketAuthenticationProvider alfrescoTicketAuthProvider;

    public SecurityConfig(
            AlfrescoAuthenticationProvider alfrescoAuthProvider,
            AlfrescoTicketAuthenticationProvider alfrescoTicketAuthProvider) {
        this.alfrescoAuthProvider = alfrescoAuthProvider;
        this.alfrescoTicketAuthProvider = alfrescoTicketAuthProvider;
    }

    /**
     * Configures the security filter chain.
     * - CSRF disabled (stateless API)
     * - Actuator endpoints public
     * - All /api/** endpoints require authentication
     * - Supports both Basic Auth and Alfresco tickets
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager authenticationManager) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(
                        new AlfrescoTicketAuthenticationFilter(authenticationManager),
                        BasicAuthenticationFilter.class
                )
                .httpBasic(httpBasic -> {
                    httpBasic.authenticationEntryPoint((request, response, authException) -> {
                        response.setHeader("WWW-Authenticate", "Basic realm=\"Alfresco Content Lake\"");
                        response.sendError(HttpStatus.UNAUTHORIZED.value(), "Alfresco credentials or ticket required");
                    });
                })
                .build();
    }

    /**
     * Configures the authentication manager with Alfresco authentication providers.
     * Ticket authentication is checked first, then username/password.
     */
    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        AuthenticationManagerBuilder authenticationManagerBuilder =
                http.getSharedObject(AuthenticationManagerBuilder.class);

        authenticationManagerBuilder
                .authenticationProvider(alfrescoTicketAuthProvider)
                .authenticationProvider(alfrescoAuthProvider);

        return authenticationManagerBuilder.build();
    }
}
