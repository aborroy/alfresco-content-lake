package org.hyland.contentlake.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specification for the ticket header encoding.
 *
 * <p>Every browser client and both ticket-authenticating services agree on the strings asserted here.
 * A disagreement does not fail loudly: the header falls through to Spring's Basic auth filter, which
 * tries to authenticate the ticket as a username and returns 401, so the symptom is a feature that
 * silently stops working rather than an error naming the cause.</p>
 */
class AlfrescoTicketHeaderTest {

    @Nested
    @DisplayName("the accepted encoding")
    class Accepted {

        @Test
        void ticketWithEmptyPasswordIsTheOneFormEveryClientSends() {
            assertThat(AlfrescoTicketHeader.extractTicket(basic("TICKET_abc123:"))).isEqualTo("TICKET_abc123");
        }

        @Test
        void surroundingWhitespaceInTheEncodedValueIsIgnored() {
            assertThat(AlfrescoTicketHeader.extractTicket("Basic  " + encode("TICKET_abc123:") + " "))
                    .isEqualTo("TICKET_abc123");
        }
    }

    @Nested
    @DisplayName("rejected ticket encodings")
    class RejectedEncodings {

        @Test
        void bareTicketWithNoSeparatorIsRejected() {
            assertThat(AlfrescoTicketHeader.extractTicket(basic("TICKET_abc123"))).isNull();
        }

        @Test
        void ticketWithANonEmptyPasswordIsRejected() {
            assertThat(AlfrescoTicketHeader.extractTicket(basic("TICKET_abc123:pass"))).isNull();
        }

        @Test
        void ticketWithATrailingSecondSeparatorIsRejected() {
            assertThat(AlfrescoTicketHeader.extractTicket(basic("TICKET_abc:123:"))).isNull();
        }
    }

    @Nested
    @DisplayName("values that are not tickets")
    class NotATicket {

        @Test
        void realBasicCredentialsAreLeftForSpringBasicAuthentication() {
            assertThat(AlfrescoTicketHeader.extractTicket(basic("admin:admin"))).isNull();
        }

        @Test
        void nullHeaderYieldsNoTicket() {
            assertThat(AlfrescoTicketHeader.extractTicket(null)).isNull();
        }

        @Test
        void nonBasicSchemeYieldsNoTicket() {
            assertThat(AlfrescoTicketHeader.extractTicket("Bearer " + encode("TICKET_abc123:"))).isNull();
        }

        @Test
        void undecodableBase64YieldsNoTicketRatherThanThrowing() {
            assertThat(AlfrescoTicketHeader.extractTicket("Basic not-base64!!")).isNull();
        }

        @Test
        void aUsernameThatMerelyContainsTicketIsNotATicket() {
            assertThat(AlfrescoTicketHeader.extractTicket(basic("not-a-TICKET_abc:pass"))).isNull();
        }
    }

    private static String basic(String credentials) {
        return "Basic " + encode(credentials);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
