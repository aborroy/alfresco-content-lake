package org.hyland.contentlake.rag.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.RagProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-principal token-bucket rate limiting for RAG/search endpoints (#75).
 *
 * <p>Registered after authentication in the security chain, so the principal is already resolved.
 * Generation endpoints ({@code /api/rag/prompt}, {@code /api/rag/graph-prompt},
 * {@code /api/rag/chat/stream}) get a tighter budget than search ({@code /api/rag/search/**}) given
 * the per-request LLM cost. Non-matching paths (health, actuator, status) are not limited.</p>
 *
 * <p>Buckets are held in-memory per {@code (principal, endpoint-class)} and are therefore
 * per-instance: a multi-instance deployment does not share limits (documented in AGENTS.md).</p>
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private enum EndpointClass { GENERATE, SEARCH }

    private final RagProperties.RateLimitProperties properties;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RagProperties ragProperties) {
        this.properties = ragProperties.getRateLimit();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        EndpointClass endpointClass = classify(request);
        if (!properties.isEnabled() || endpointClass == null) {
            chain.doFilter(request, response);
            return;
        }

        String principal = resolvePrincipal(request);
        String key = endpointClass + "|" + principal;
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(endpointClass));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        log.debug("Rate limit exceeded for principal={} endpoint={}; retry after {}s",
                principal, endpointClass, retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"rate_limit_exceeded\",\"retryAfterSeconds\":" + retryAfterSeconds + "}");
    }

    private Bucket newBucket(EndpointClass endpointClass) {
        int perMinute = endpointClass == EndpointClass.GENERATE
                ? properties.getGenerateRequestsPerMinute()
                : properties.getSearchRequestsPerMinute();
        Bandwidth limit = Bandwidth.builder()
                .capacity(perMinute)
                .refillGreedy(perMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private static EndpointClass classify(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        if (path.startsWith("/api/rag/search/")) {
            return EndpointClass.SEARCH;
        }
        if (path.equals("/api/rag/prompt")
                || path.equals("/api/rag/graph-prompt")
                || path.startsWith("/api/rag/chat/stream")) {
            return EndpointClass.GENERATE;
        }
        return null;
    }

    private static String resolvePrincipal(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return "anon:" + request.getRemoteAddr();
    }
}
