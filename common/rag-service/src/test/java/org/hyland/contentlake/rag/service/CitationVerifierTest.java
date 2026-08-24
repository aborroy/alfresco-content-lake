package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CitationVerifierTest {

    @Mock
    StructuredLlmCaller structuredLlmCaller;

    private RagProperties properties;
    private CitationVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        verifier = new CitationVerifier(structuredLlmCaller, properties);
    }

    private void enable() {
        properties.getCitation().getVerify().setEnabled(true);
    }

    private static List<SearchHit> hits() {
        return List.of(SearchHit.builder().rank(1).score(0.9)
                .chunkText("Records are retained for seven years.").build());
    }

    private static CitationVerifier.FaithfulnessVerdict verdict(boolean verified, List<String> unsupported) {
        CitationVerifier.FaithfulnessVerdict v = new CitationVerifier.FaithfulnessVerdict();
        v.setVerified(verified);
        v.setUnsupportedClaims(unsupported);
        return v;
    }

    @Test
    void disabled_returnsNullWithoutCallingLlm() {
        assertThat(verifier.verify("some answer", hits())).isNull();
        verifyNoInteractions(structuredLlmCaller);
    }

    @Test
    void blankAnswerOrNoContext_returnsNull() {
        enable();
        assertThat(verifier.verify("  ", hits())).isNull();
        assertThat(verifier.verify("answer", List.of())).isNull();
        verifyNoInteractions(structuredLlmCaller);
    }

    @Test
    void supportedAnswer_isVerified() {
        enable();
        when(structuredLlmCaller.call(any(), any(), eq(CitationVerifier.FaithfulnessVerdict.class), any(), any()))
                .thenReturn(verdict(true, List.of()));

        CitationVerifier.VerificationResult result =
                verifier.verify("Records are kept seven years.", hits());

        assertThat(result).isNotNull();
        assertThat(result.verified()).isTrue();
        assertThat(result.unsupportedClaims()).isEmpty();
    }

    @Test
    void unsupportedClaimForcesNotVerified_evenIfModelSaysVerified() {
        enable();
        // Model contradicts itself (verified=true but lists a claim); the list wins.
        when(structuredLlmCaller.call(any(), any(), eq(CitationVerifier.FaithfulnessVerdict.class), any(), any()))
                .thenReturn(verdict(true, List.of("Records are kept ten years.")));

        CitationVerifier.VerificationResult result =
                verifier.verify("Records are kept ten years.", hits());

        assertThat(result.verified()).isFalse();
        assertThat(result.unsupportedClaims()).containsExactly("Records are kept ten years.");
    }
}
