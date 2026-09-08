package org.hyland.contentlake.rag.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import org.hyland.contentlake.rag.config.RagProperties;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A registry that records every observation it sees, plus a {@link RagObservations} wired to it.
 *
 * <p>Written rather than using {@code TestObservationRegistry}: the TCK exposes its recorded contexts
 * only through {@code TestObservationRegistryAssert}, which can assert on a named key but cannot
 * enumerate every key and value. The redaction test needs exactly that enumeration, because it asserts
 * a whitelist ("no span carries this sentinel anywhere") rather than a per-key blacklist, so it fails
 * when someone later adds a new content-bearing attribute.</p>
 */
final class RecordingObservations {

    private final List<Recorded> recorded = new ArrayList<>();
    private final ObservationRegistry registry = ObservationRegistry.create();
    private final RagObservations observations;

    /**
     * One observed span: its name, its ancestor names nearest-first, and every key-value on it.
     *
     * <p>The ancestor chain is captured here rather than reconstructed by name afterwards, because span
     * names repeat: Spring AI emits several {@code spring.ai.advisor} spans per request, so a
     * name-based walk finds the wrong one and can loop.</p>
     */
    record Recorded(String name, List<String> ancestors, Map<String, String> low, Map<String, String> high) {

        /** Immediate parent's name, or null for a root. */
        String parentName() {
            return ancestors.isEmpty() ? null : ancestors.getFirst();
        }

        /** Every key and value, concatenated, for whitelist assertions. */
        String allText() {
            StringBuilder text = new StringBuilder();
            low.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
            high.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
            return text.toString();
        }
    }

    RecordingObservations(boolean payloadsEnabled, boolean captureContent) {
        this(payloadsEnabled, captureContent, 2000, 20);
    }

    RecordingObservations(boolean payloadsEnabled, boolean captureContent, int maxContentChars,
                          int maxChunksRecorded) {
        registry.observationConfig().observationHandler(new Recorder());

        RagProperties props = new RagProperties();
        props.getObservability().setPayloadsEnabled(payloadsEnabled);
        props.getObservability().setCaptureContent(captureContent);
        props.getObservability().setMaxContentChars(maxContentChars);
        props.getObservability().setMaxChunksRecorded(maxChunksRecorded);

        this.observations = new RagObservations(provider(registry), provider(props));
    }

    RagObservations observations() {
        return observations;
    }

    /**
     * The underlying registry, for collaborators that take one directly. Spring AI's ChatClient does:
     * built without it, it wraps the advisor chain in a scope-handling noop observation that unparents
     * every span the advisor creates.
     */
    ObservationRegistry registry() {
        return registry;
    }

    List<Recorded> recorded() {
        return List.copyOf(recorded);
    }

    List<String> names() {
        return recorded.stream().map(Recorded::name).toList();
    }

    Recorded byName(String name) {
        return recorded.stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no observation named " + name
                        + " (recorded: " + names() + ")"));
    }

    /** Every key and value from every recorded span, concatenated. */
    String allText() {
        StringBuilder text = new StringBuilder();
        recorded.forEach(r -> text.append(r.allText()));
        return text.toString();
    }

    /**
     * As {@link #allText()} but only over spans this codebase creates.
     *
     * <p>Spring AI's ChatClient contributes its own spans ({@code spring.ai.advisor},
     * {@code gen_ai.*}) once it is given the registry, and those carry their own key-values. Payload
     * assertions about what <em>this</em> instrumentation attaches have to exclude them.</p>
     */
    String ourText() {
        StringBuilder text = new StringBuilder();
        recorded.stream().filter(r -> r.name().startsWith("rag."))
                .forEach(r -> text.append(r.allText()));
        return text.toString();
    }

    /**
     * Names on the parent chain above the span called {@code name}, nearest first.
     *
     * <p>Ancestry rather than direct parenthood, because Spring AI's own advisor and model spans sit
     * between the request span and the ones the advisor creates. They are legitimate intermediates: the
     * trace being connected is what matters.</p>
     */
    List<String> ancestorNames(String name) {
        return byName(name).ancestors();
    }

    /**
     * Records on stop, so key-values attached during the work are included. Reading them at start
     * would capture only the pre-start tags and silently miss every payload.
     */
    private final class Recorder implements ObservationHandler<Observation.Context> {

        @Override
        public void onStop(Observation.Context context) {
            recorded.add(new Recorded(
                    context.getName(),
                    ancestorsOf(context),
                    toMap(context.getLowCardinalityKeyValues()),
                    toMap(context.getHighCardinalityKeyValues())));
        }

        private static List<String> ancestorsOf(Observation.Context context) {
            List<String> ancestors = new ArrayList<>();
            ObservationView parent = context.getParentObservation();
            // Bounded: a malformed chain must not hang the test run.
            while (parent != null && ancestors.size() < 32) {
                ancestors.add(parent.getContextView().getName());
                parent = parent.getContextView().getParentObservation();
            }
            return ancestors;
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        private static Map<String, String> toMap(Iterable<KeyValue> keyValues) {
            Map<String, String> map = new LinkedHashMap<>();
            keyValues.forEach(kv -> map.put(kv.getKey(), kv.getValue()));
            return map;
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public Stream<T> stream() { return Stream.of(value); }
        };
    }
}
