package org.hyland.contentlake.rag.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hyland.contentlake.rag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagQueryCacheTest {

    @SuppressWarnings("unchecked")
    private static RagQueryCache cache(boolean enabled) {
        RagProperties props = new RagProperties();
        props.getCache().setEnabled(enabled);
        props.getCache().setTtlSeconds(60);
        props.getCache().setMaxSize(100);
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
        return new RagQueryCache(props, provider);
    }

    @Test
    void normalize_collapsesCaseAndWhitespace() {
        assertThat(RagQueryCache.normalize("  What   IS  Hybrid Search? "))
                .isEqualTo("what is hybrid search?");
        assertThat(RagQueryCache.normalize(null)).isEmpty();
    }

    @Test
    void principalScope_distinguishesPrincipals() {
        Authentication alice = mock(Authentication.class);
        when(alice.getName()).thenReturn("alice");

        assertThat(RagQueryCache.principalScope(null)).isEqualTo("anon");
        assertThat(RagQueryCache.principalScope(alice)).isEqualTo("u:alice");
    }

    @Test
    void disabled_isPassThrough() {
        RagQueryCache cache = cache(false);
        assertThat(cache.isEnabled()).isFalse();

        cache.putResult("k", "v");
        assertThat(cache.<String>getResult("k")).isNull();

        AtomicInteger loads = new AtomicInteger();
        cache.embedQuery("q", "mxbai", () -> {
            loads.incrementAndGet();
            return List.of(1.0);
        });
        cache.embedQuery("q", "mxbai", () -> {
            loads.incrementAndGet();
            return List.of(1.0);
        });
        // Loader runs every time when caching is off.
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void enabled_resultRoundTrips() {
        RagQueryCache cache = cache(true);
        assertThat(cache.isEnabled()).isTrue();
        assertThat(cache.<String>getResult("missing")).isNull();

        cache.putResult("k", "v");
        assertThat(cache.<String>getResult("k")).isEqualTo("v");
    }

    @Test
    void enabled_embeddingCachedPerTextAndType() {
        RagQueryCache cache = cache(true);
        AtomicInteger loads = new AtomicInteger();

        List<Double> first = cache.embedQuery("what is X", "mxbai", () -> {
            loads.incrementAndGet();
            return List.of(0.1, 0.2);
        });
        List<Double> second = cache.embedQuery("  What IS   X ", "mxbai", () -> {
            loads.incrementAndGet();
            return List.of(9.9);
        });

        // Second call is a normalized-key hit: loader not invoked again, same vector returned.
        assertThat(loads.get()).isEqualTo(1);
        assertThat(second).isEqualTo(first);

        // A different embedding type is a distinct key -> miss -> loader runs.
        cache.embedQuery("what is X", "other-model", () -> {
            loads.incrementAndGet();
            return List.of(0.3);
        });
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void enabled_emptyEmbeddingNotCached() {
        RagQueryCache cache = cache(true);
        AtomicInteger loads = new AtomicInteger();

        cache.embedQuery("q", "mxbai", () -> {
            loads.incrementAndGet();
            return List.of();
        });
        cache.embedQuery("q", "mxbai", () -> {
            loads.incrementAndGet();
            return List.of();
        });
        // An empty vector is never stored, so the loader runs again.
        assertThat(loads.get()).isEqualTo(2);
    }
}
