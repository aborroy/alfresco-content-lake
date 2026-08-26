package org.hyland.contentlake.rag.security;

import jakarta.servlet.FilterChain;
import org.hyland.contentlake.rag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private RagProperties propsWith(boolean enabled, int generateRpm, int searchRpm) {
        RagProperties props = new RagProperties();
        props.getRateLimit().setEnabled(enabled);
        props.getRateLimit().setGenerateRequestsPerMinute(generateRpm);
        props.getRateLimit().setSearchRequestsPerMinute(searchRpm);
        return props;
    }

    private MockHttpServletRequest req(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    @Test
    void disabled_passesThroughWithoutLimiting() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(propsWith(false, 1, 1));
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            filter.doFilter(req("/api/rag/prompt"), new MockHttpServletResponse(), chain);
        }
        verify(chain, times(5)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void generationEndpoint_returns429AfterBudgetExhausted() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(propsWith(true, 2, 60));
        FilterChain chain = mock(FilterChain.class);

        // First 2 consume the bucket.
        filter.doFilter(req("/api/rag/prompt"), new MockHttpServletResponse(), chain);
        filter.doFilter(req("/api/rag/prompt"), new MockHttpServletResponse(), chain);
        // Third is blocked.
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(req("/api/rag/prompt"), blocked, chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(blocked.getContentAsString()).contains("rate_limit_exceeded");
    }

    @Test
    void nonLimitedPath_isNeverThrottled() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(propsWith(true, 1, 1));
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            filter.doFilter(req("/api/status"), new MockHttpServletResponse(), chain);
        }
        verify(chain, times(5)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
