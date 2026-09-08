package org.hyland.contentlake.rag.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the span-payload API #116 adds, and specifically its two independent gates: payloads, and
 * content within them.
 */
class RagObservationsTest {

    @Test
    void observe_recordsLowCardinalityTagsAndHighCardinalityAttributes() {
        RecordingObservations recorder = new RecordingObservations(true, false);

        String result = recorder.observations().observe("rag.request",
                span -> span.tag("rag.path", "sync"),
                span -> {
                    span.attr("rag.retrieve.hits", 3L);
                    return "answer";
                });

        assertThat(result).isEqualTo("answer");
        var span = recorder.byName("rag.request");
        // The split is the safety mechanism: a low-cardinality key-value becomes a metric tag as well,
        // so anything unbounded has to stay high cardinality or it multiplies the meter registry.
        assertThat(span.low()).containsEntry("rag.path", "sync");
        assertThat(span.high()).containsEntry("rag.retrieve.hits", "3");
    }

    @Test
    void observe_withPayloadsDisabled_createsTheSpanButAttachesNothing() {
        // The spans from #73 must survive with payloads off: they exist for every deployment already,
        // so gating the whole wrapper would remove instrumentation rather than just its payload.
        RecordingObservations recorder = new RecordingObservations(false, false);

        recorder.observations().observe("rag.request",
                span -> span.tag("rag.path", "sync"),
                span -> span.attr("rag.retrieve.hits", 3L));

        var span = recorder.byName("rag.request");
        assertThat(span.low()).isEmpty();
        assertThat(span.high()).isEmpty();
        assertThat(recorder.observations().payloadsEnabled()).isFalse();
    }

    @Test
    void noopInstance_recordsNoObservationAtAll() {
        String result = RagObservations.NOOP.observe("rag.request", span -> {
            span.tag("rag.path", "sync").attr("rag.retrieve.hits", 3L).content("rag.query.text", "secret");
            return "answer";
        });

        assertThat(result).isEqualTo("answer");
        assertThat(RagObservations.NOOP.payloadsEnabled()).isFalse();
        assertThat(RagObservations.NOOP.contentCaptureEnabled()).isFalse();
    }

    @Test
    void content_isDroppedWhenCaptureContentIsFalse() {
        RecordingObservations recorder = new RecordingObservations(true, false);

        recorder.observations().observe("rag.request",
                span -> span.content("rag.query.text", "SENTINEL_QUESTION"));

        // The second gate, and the one that must hold unconditionally: a caller who forgets to guard a
        // payload loop still cannot leak content.
        assertThat(recorder.allText()).doesNotContain("SENTINEL_QUESTION");
        assertThat(recorder.observations().contentCaptureEnabled()).isFalse();
    }

    @Test
    void content_isRecordedWhenCaptureContentIsTrue() {
        RecordingObservations recorder = new RecordingObservations(true, true);

        recorder.observations().observe("rag.request",
                span -> span.content("rag.query.text", "SENTINEL_QUESTION"));

        assertThat(recorder.allText()).contains("SENTINEL_QUESTION");
        assertThat(recorder.observations().contentCaptureEnabled()).isTrue();
    }

    @Test
    void content_isTruncatedToMaxContentChars() {
        RecordingObservations recorder = new RecordingObservations(true, true, 8, 20);

        recorder.observations().observe("rag.request",
                span -> span.content("rag.answer.text", "x".repeat(500)));

        assertThat(recorder.byName("rag.request").high().get("rag.answer.text")).hasSize(8);
    }

    @Test
    void content_ignoresNullRatherThanRecordingTheLiteralNull() {
        RecordingObservations recorder = new RecordingObservations(true, true);

        recorder.observations().observe("rag.request", span -> span.content("rag.answer.text", null));

        assertThat(recorder.byName("rag.request").high()).doesNotContainKey("rag.answer.text");
    }

    @Test
    void close_isIdempotent() {
        // The streaming path can reach a terminal signal through the completion callback, the error
        // callback, onCompletion and onTimeout; a second stop would otherwise be recorded twice.
        RecordingObservations recorder = new RecordingObservations(true, false);

        RagObservations.Span span = recorder.observations()
                .start("rag.request", s -> s.tag("rag.path", "stream"));
        span.attr("rag.retrieve.hits", 1L);
        span.close();
        span.close();
        span.close();

        assertThat(recorder.names()).containsExactly("rag.request");
    }

    @Test
    void start_returnsASpanThatParentsSpansOpenedInsideItsScope() throws Exception {
        RecordingObservations recorder = new RecordingObservations(true, false);

        RagObservations.Span root = recorder.observations().start("rag.request", s -> { });
        try (AutoCloseable ignored = root.openScope()) {
            recorder.observations().observe("rag.retrieve", span -> "done");
        }
        root.close();

        assertThat(recorder.names()).containsExactlyInAnyOrder("rag.request", "rag.retrieve");
        assertThat(recorder.byName("rag.retrieve").parentName()).isEqualTo("rag.request");
    }

    @Test
    void observe_recordsAnErrorAndRethrowsIt() {
        RecordingObservations recorder = new RecordingObservations(true, false);

        try {
            recorder.observations().observe("rag.generate", span -> {
                throw new IllegalStateException("model unavailable");
            });
        } catch (IllegalStateException expected) {
            // rethrown, as it must be: instrumentation cannot swallow a failure.
        }

        assertThat(recorder.names()).containsExactly("rag.generate");
    }

    @Test
    void theSupplierApiFrom73StillWorks() {
        RecordingObservations recorder = new RecordingObservations(false, false);

        assertThat(recorder.observations().<String>observe("rag.search.vector", () -> "hits"))
                .isEqualTo("hits");
        assertThat(recorder.names()).containsExactly("rag.search.vector");
    }
}
