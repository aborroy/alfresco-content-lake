package org.hyland.nuxeo.contentlake.live.config;

import jakarta.servlet.DispatcherType;
import org.hyland.nuxeo.contentlake.config.NuxeoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Secures the Nuxeo live ingester.
 *
 * <p>The live ingester exposes no user-facing REST API. Everything is authenticated
 * except the health and info probes, which remain public so that the container
 * orchestrator can reach them without credentials.</p>
 */
@Configuration
@EnableWebSecurity
public class NuxeoLiveSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // INVARIANT: default deny. Only the container probes above are public, so a new
                        // endpoint is authenticated unless someone deliberately exempts it here. Do not
                        // reintroduce anyRequest().permitAll().
                        .anyRequest().authenticated()
                )
                .httpBasic(httpBasic -> {})
                .build();
    }

    @Bean
    UserDetailsService userDetailsService(NuxeoProperties props) {
        return new InMemoryUserDetailsManager(
                User.withUsername(props.getUsername())
                        .password("{noop}" + props.getPassword())
                        .roles("LIVE_ADMIN")
                        .build()
        );
    }
}
