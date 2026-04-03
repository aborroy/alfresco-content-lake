package org.hyland.contentlake.rag.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Returns 401 for RAG API failures without triggering the browser's native
 * HTTP Basic login dialog.
 *
 * <p>These endpoints are consumed by the UI via fetch/XHR, so surfacing the
 * Basic auth challenge causes a looping browser popup instead of letting the
 * application handle the unauthorized response.</p>
 */
public class RagAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String REALM = "Basic realm=\"RAG Service\"";

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (!isRagApiRequest(request)) {
            response.setHeader("WWW-Authenticate", REALM);
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Authentication required\"}");
    }

    private boolean isRagApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/api/rag");
    }
}
