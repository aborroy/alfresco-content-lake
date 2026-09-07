package org.hyland.contentlake.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Reads Alfresco tickets out of an HTTP Basic {@code Authorization} header.
 *
 * <p>An Alfresco ticket is not a username and password pair, but browser clients carry it in a Basic
 * header because that is what the repository itself accepts. Two services authenticate tickets this
 * way, the Alfresco batch ingester and rag-service, and both delegate here so the accepted encoding
 * is defined in exactly one place. Two independent copies of this parsing are what let the clients
 * drift onto two different encodings to begin with.</p>
 *
 * <h2>Accepted encodings</h2>
 *
 * <p>{@code base64(TICKET_xxx:)} is the form every client should send: the ticket as the username,
 * with an empty password. {@code base64(TICKET_xxx)}, the bare ticket with no separator, is also
 * accepted for now, because a deployed ACA extension bundle may predate the switch to the first
 * form. Once no such bundle can still be running, drop it and accept one encoding only.</p>
 */
public final class AlfrescoTicketHeader {

    /** Prefix carried by every Alfresco ticket. */
    public static final String TICKET_PREFIX = "TICKET_";

    private static final String BASIC_PREFIX = "Basic ";

    private AlfrescoTicketHeader() {
    }

    /**
     * Extracts the Alfresco ticket carried by an {@code Authorization} header value.
     *
     * @param authorizationHeader raw header value, may be {@code null}
     * @return the ticket, or {@code null} when the header is absent, is not Basic, is not valid
     *         base64, or does not carry a ticket in an accepted encoding
     */
    public static String extractTicket(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BASIC_PREFIX)) {
            return null;
        }

        String decoded;
        try {
            byte[] raw = Base64.getDecoder().decode(authorizationHeader.substring(BASIC_PREFIX.length()).trim());
            decoded = new String(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }

        if (!decoded.startsWith(TICKET_PREFIX)) {
            return null;
        }

        int separator = decoded.indexOf(':');
        return separator >= 0 ? decoded.substring(0, separator) : decoded;
    }
}
