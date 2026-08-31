package org.hyland.contentlake.rag.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Thin wrapper over the Micrometer {@link ObservationRegistry} that names spans for the key RAG
 * pipeline steps (#73). Each named observation becomes an OpenTelemetry span via the tracing bridge,
 * so a single request shows which hxpr query, embedding call, reranker call, or LLM generation was
 * the actual bottleneck - detail the coarse {@code searchTimeMs}/{@code generationTimeMs} fields on
 * {@code RagPromptResponse} cannot provide.
 *
 * <p>When no registry is available (or tracing is not sampled), observation is effectively a no-op:
 * the work runs directly and no span is created, so instrumentation never changes behaviour or adds
 * meaningful overhead on the unsampled path.</p>
 */
@Component
public class RagObservations {

    private final ObservationRegistry registry;

    public RagObservations(ObjectProvider<ObservationRegistry> registryProvider) {
        ObservationRegistry resolved = registryProvider.getIfAvailable();
        this.registry = resolved != null ? resolved : ObservationRegistry.NOOP;
    }

    /** Runs {@code work} inside a named span and returns its value. */
    public <T> T observe(String name, Supplier<T> work) {
        if (registry == ObservationRegistry.NOOP) {
            return work.get();
        }
        return Observation.createNotStarted(name, registry).observe(work);
    }

    /** Runs {@code work} inside a named span. */
    public void observe(String name, Runnable work) {
        if (registry == ObservationRegistry.NOOP) {
            work.run();
            return;
        }
        Observation.createNotStarted(name, registry).observe(work);
    }
}
