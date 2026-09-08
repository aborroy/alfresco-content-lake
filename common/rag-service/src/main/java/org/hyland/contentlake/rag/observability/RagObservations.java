package org.hyland.contentlake.rag.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.hyland.contentlake.rag.config.RagProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Thin wrapper over the Micrometer {@link ObservationRegistry} that names spans for the key RAG
 * pipeline steps (#73) and attaches their payloads (#116). Each named observation becomes an
 * OpenTelemetry span via the tracing bridge, so a single request shows which hxpr query, embedding
 * call, reranker call, or LLM generation was the actual bottleneck, and what was retrieved, prompted
 * and spent doing it.
 *
 * <p>When no registry is available, observation is a no-op: the work runs directly and no span is
 * created, so instrumentation never changes behaviour.</p>
 *
 * <h3>Two independent switches, and why</h3>
 * <p>{@code rag.observability.payloads-enabled} governs whether anything is attached at all. It is
 * separate from sampling because with {@code spring-boot-starter-actuator} on the classpath the
 * registry is never {@code NOOP}, so the spans exist on every request whether or not they are
 * exported; without this flag, payload-building would run on every request too.</p>
 *
 * <p>{@code rag.observability.capture-content} governs whether user content travels with them, and is
 * a data-protection decision rather than a verbosity one: a question, a chunk's text and a document's
 * path are content under someone's ACL, and a trace backend has its own, usually weaker, access
 * model. {@link Span#content} drops its value unless it is on, so a caller that forgets to guard a
 * payload loop still cannot leak content.</p>
 *
 * <h3>Tags versus attributes</h3>
 * <p>Micrometer turns low-cardinality key-values into metric tags <em>and</em> span attributes, and
 * only forwards those added before the observation starts. Anything unbounded added as a tag
 * multiplies the meter registry, so {@link Span#tag} is legal only inside the {@code before} callback
 * and is reserved for values bounded by deployment configuration; everything per-request goes through
 * {@link Span#attr}, which is a span attribute only.</p>
 */
@Component
public class RagObservations {

    /**
     * Creates no spans and attaches nothing.
     *
     * <p>For unit tests and for collaborators constructed without a registry. Preferred over a
     * {@code null} field plus a null check at every call site.</p>
     */
    public static final RagObservations NOOP = new RagObservations();

    private final ObservationRegistry registry;
    private final boolean payloads;
    private final boolean captureContent;
    private final int maxContentChars;

    public RagObservations(ObjectProvider<ObservationRegistry> registryProvider,
                           ObjectProvider<RagProperties> ragPropertiesProvider) {
        ObservationRegistry resolved = registryProvider.getIfAvailable();
        this.registry = resolved != null ? resolved : ObservationRegistry.NOOP;

        RagProperties props = ragPropertiesProvider.getIfAvailable();
        RagProperties.ObservabilityProperties observability = props != null
                ? props.getObservability()
                : new RagProperties.ObservabilityProperties();
        this.payloads = observability.isPayloadsEnabled();
        this.captureContent = observability.isCaptureContent();
        this.maxContentChars = observability.getMaxContentChars();
    }

    private RagObservations() {
        this.registry = ObservationRegistry.NOOP;
        this.payloads = false;
        this.captureContent = false;
        this.maxContentChars = 0;
    }

    /** Whether payload attachment is on. Guard any O(n) payload construction with this. */
    public boolean payloadsEnabled() {
        return payloads && registry != ObservationRegistry.NOOP;
    }

    /**
     * Whether user content may be attached. Informational only: {@link Span#content} enforces it
     * regardless of what the caller checks.
     */
    public boolean contentCaptureEnabled() {
        return payloadsEnabled() && captureContent;
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

    /** As {@link #observe(String, Consumer, Function)} with no pre-start tags. */
    public <T> T observe(String name, Function<Span, T> work) {
        return observe(name, span -> { }, work);
    }

    /**
     * Runs {@code work} inside a named span, giving it the span so it can attach what only becomes
     * known as it runs, such as token usage or the ids of the chunks it retrieved.
     *
     * @param name   span name
     * @param before runs before the span starts; the only place {@link Span#tag} is legal
     * @param work   receives the same span and may call {@link Span#attr} at any point
     */
    public <T> T observe(String name, Consumer<Span> before, Function<Span, T> work) {
        if (registry == ObservationRegistry.NOOP) {
            return work.apply(NoopSpan.INSTANCE);
        }
        Observation observation = Observation.createNotStarted(name, registry);
        LiveSpan span = new LiveSpan(observation, captureContent, maxContentChars, payloads);
        before.accept(span);
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            return work.apply(span);
        } catch (Throwable t) {
            observation.error(t);
            throw t;
        } finally {
            observation.stop();
        }
    }

    /**
     * Starts a span the caller must close itself.
     *
     * <p>For the streaming path, where the work does not return inside a lambda: the answer arrives
     * over a reactive stream and the span has to outlive the method that opened it. The caller must
     * close it on every terminal signal; {@link Span#close()} is idempotent so covering
     * {@code onCompletion}, {@code onTimeout} and both subscriber callbacks is safe.</p>
     */
    public Span start(String name, Consumer<Span> before) {
        if (registry == ObservationRegistry.NOOP) {
            return NoopSpan.INSTANCE;
        }
        Observation observation = Observation.createNotStarted(name, registry);
        LiveSpan span = new LiveSpan(observation, captureContent, maxContentChars, payloads);
        before.accept(span);
        observation.start();
        return span;
    }

    /**
     * A handle to one span. Every mutator returns {@code this} and is a no-op on the no-op path, so a
     * caller never branches on whether observation is active.
     */
    public interface Span extends AutoCloseable {

        /**
         * Low-cardinality tag: becomes a metric tag as well as a span attribute. Legal only before
         * the span starts, so only inside the {@code before} callback. Reserve for values bounded by
         * deployment configuration.
         */
        Span tag(String key, String value);

        /** High-cardinality span attribute. Legal at any point before {@link #close()}. */
        Span attr(String key, String value);

        Span attr(String key, long value);

        Span attr(String key, boolean value);

        /**
         * High-cardinality attribute carrying user content. Dropped entirely unless content capture
         * is on, and truncated to {@code rag.observability.max-content-chars} when it is.
         */
        Span content(String key, String value);

        /** Records an error against the span without closing it. */
        Span error(Throwable t);

        /**
         * Makes this span current on the calling thread, so spans created inside the returned scope
         * are its children. For the subscribe boundary on the streaming path.
         */
        AutoCloseable openScope();

        /** Stops the span. Idempotent. */
        @Override
        void close();
    }

    /** Attaches nothing. A singleton, so the no-op path allocates nothing. */
    private static final class NoopSpan implements Span {
        private static final NoopSpan INSTANCE = new NoopSpan();
        private static final AutoCloseable NOOP_SCOPE = () -> { };

        @Override public Span tag(String key, String value) { return this; }
        @Override public Span attr(String key, String value) { return this; }
        @Override public Span attr(String key, long value) { return this; }
        @Override public Span attr(String key, boolean value) { return this; }
        @Override public Span content(String key, String value) { return this; }
        @Override public Span error(Throwable t) { return this; }
        @Override public AutoCloseable openScope() { return NOOP_SCOPE; }
        @Override public void close() { }
    }

    private static final class LiveSpan implements Span {

        private final Observation observation;
        private final boolean captureContent;
        private final int maxContentChars;
        private final boolean payloads;
        private volatile boolean closed;

        private LiveSpan(Observation observation, boolean captureContent, int maxContentChars,
                         boolean payloads) {
            this.observation = observation;
            this.captureContent = captureContent;
            this.maxContentChars = maxContentChars;
            this.payloads = payloads;
        }

        @Override
        public Span tag(String key, String value) {
            if (payloads && value != null) {
                observation.lowCardinalityKeyValue(KeyValue.of(key, value));
            }
            return this;
        }

        @Override
        public Span attr(String key, String value) {
            if (payloads && value != null) {
                observation.highCardinalityKeyValue(KeyValue.of(key, value));
            }
            return this;
        }

        @Override
        public Span attr(String key, long value) {
            return attr(key, Long.toString(value));
        }

        @Override
        public Span attr(String key, boolean value) {
            return attr(key, Boolean.toString(value));
        }

        @Override
        public Span content(String key, String value) {
            if (!captureContent || value == null) {
                return this;
            }
            String truncated = value.length() <= maxContentChars
                    ? value
                    : value.substring(0, Math.max(0, maxContentChars));
            return attr(key, truncated);
        }

        @Override
        public Span error(Throwable t) {
            observation.error(t);
            return this;
        }

        @Override
        public AutoCloseable openScope() {
            return observation.openScope();
        }

        @Override
        public void close() {
            // Idempotent: the streaming path can reach a terminal signal through the completion
            // callback, the error callback, onCompletion and onTimeout, and Micrometer would
            // otherwise stop the same observation more than once.
            if (closed) {
                return;
            }
            closed = true;
            observation.stop();
        }
    }
}
