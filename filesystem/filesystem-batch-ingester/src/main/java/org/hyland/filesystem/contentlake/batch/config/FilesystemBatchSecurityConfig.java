package org.hyland.filesystem.contentlake.batch.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * Secures the filesystem batch ingester.
 *
 * <p>Spring Security is on this module's classpath transitively through
 * {@code content-lake-core}. Without an application-defined chain, Boot's default chain applies and
 * authenticates every path against a password generated at each boot, which leaves the documented
 * sync trigger uncallable. This chain replaces it.</p>
 *
 * <p>Unlike the Alfresco and Nuxeo ingesters there is no source repository to authenticate against,
 * so a single configured account guards the API. Credentials come from
 * {@code filesystem.batch.security.*} and have no defaults on purpose.</p>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(FilesystemBatchProperties.class)
public class FilesystemBatchSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Async/error redispatches can happen after the initial authenticated request
                        // has already started.
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
    UserDetailsService userDetailsService(FilesystemBatchProperties props) {
        FilesystemBatchProperties.Security security = props.getSecurity();
        require(security.getUsername(), "filesystem.batch.security.username");
        require(security.getPassword(), "filesystem.batch.security.password");

        return new InMemoryUserDetailsManager(
                User.withUsername(security.getUsername())
                        .password("{noop}" + security.getPassword())
                        .roles("SYNC_ADMIN")
                        .build()
        );
    }

    /**
     * Fails startup rather than falling back to a default. A default password here would be a
     * published credential on a service whose only endpoint triggers a full re-ingest.
     */
    private static void require(String value, String property) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    property + " must be set. The filesystem batch ingester has no source repository to "
                            + "authenticate against and ships no default credential.");
        }
    }
}
