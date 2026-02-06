package org.alfresco.contentlake.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the batch application.
 *
 * <p>This configuration disables CSRF protection and allows all incoming
 * HTTP requests without authentication. It is intended for non-interactive
 * batch or internal use cases.</p>
 */
@Configuration
public class SecurityConfig {

    /**
     * Defines the security filter chain for the application.
     * <p>CSRF is disabled and all requests are permitted.</p>
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize ->
                        authorize.anyRequest().permitAll()
                )
                .build();
    }
}
