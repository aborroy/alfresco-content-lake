package org.hyland.contentlake.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SecurityContextService")
class SecurityContextServiceTest {

    private final SecurityContextService service = new SecurityContextService();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(org.springframework.security.core.Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    @Nested
    @DisplayName("returns the caller")
    class Authenticated {

        @Test
        void returnsTheAuthenticatedUsername() {
            authenticate(new UsernamePasswordAuthenticationToken("alice", "secret", List.of()));

            assertThat(service.getCurrentUsername()).isEqualTo("alice");
        }

        @Test
        void returnsANameContainingCharactersThatNeedEscapingDownstream() {
            authenticate(new UsernamePasswordAuthenticationToken("o'brien", "secret", List.of()));

            assertThat(service.getCurrentUsername()).isEqualTo("o'brien");
        }
    }

    @Nested
    @DisplayName("fails closed")
    class FailsClosed {

        @Test
        void throwsWhenThereIsNoSecurityContext() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(service::getCurrentUsername)
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                    .hasMessageContaining("No authenticated principal");
        }

        @Test
        void throwsWhenTheTokenIsNotAuthenticated() {
            authenticate(new TestingAuthenticationToken("alice", "secret"));

            assertThatThrownBy(service::getCurrentUsername)
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        }

        @Test
        void throwsOnAnAnonymousTokenEvenThoughItReportsItselfAuthenticated() {
            AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                    "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
            authenticate(anonymous);

            assertThat(anonymous.isAuthenticated()).isTrue();
            assertThatThrownBy(service::getCurrentUsername)
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        }

        @Test
        void throwsWhenThePrincipalNameIsBlank() {
            authenticate(new UsernamePasswordAuthenticationToken("   ", "secret", List.of()));

            assertThatThrownBy(service::getCurrentUsername)
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                    .hasMessageContaining("no name");
        }

        @Test
        void neverReturnsAPlaceholderPrincipal() {
            SecurityContextHolder.clearContext();

            // The regression this guards: returning "anonymous" resolved to a set of authorities and
            // produced a permission filter for a caller that does not exist.
            assertThatThrownBy(service::getCurrentUsername)
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        }
    }
}
