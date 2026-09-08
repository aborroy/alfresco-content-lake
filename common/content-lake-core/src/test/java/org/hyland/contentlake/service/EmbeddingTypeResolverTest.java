package org.hyland.contentlake.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the single derivation both the embedding write and the embedding clear path use (#113).
 * When the two disagree, children written under one name cannot be named by the other, so they
 * survive a re-sync and keep answering queries.
 */
class EmbeddingTypeResolverTest {

    @Test
    void derivesTheTypeFromTheConfiguredModel() {
        // The configured value everywhere in this repository. The slash cannot go into a sys_name.
        assertThat(EmbeddingTypeResolver.toEmbeddingType("ai/mxbai-embed-large"))
                .isEqualTo("ai-mxbai-embed-large");
    }

    @Test
    void leavesAnAlreadySafeModelNameUnchanged() {
        assertThat(EmbeddingTypeResolver.toEmbeddingType("nomic-embed-text"))
                .isEqualTo("nomic-embed-text");
    }

    @Test
    void lowercasesAndReplacesEveryUnsafeCharacter() {
        assertThat(EmbeddingTypeResolver.toEmbeddingType("Provider:Model@v2 (beta)"))
                .isEqualTo("provider-model-v2-beta");
    }

    @Test
    void keepsDotsUnderscoresAndHyphens_soVersionedModelNamesStayReadable() {
        assertThat(EmbeddingTypeResolver.toEmbeddingType("text_embedding-3.large"))
                .isEqualTo("text_embedding-3.large");
    }

    @Test
    void collapsesRunsOfReplacedCharacters() {
        assertThat(EmbeddingTypeResolver.toEmbeddingType("a///b   c")).isEqualTo("a-b-c");
    }

    @Test
    void truncatesToTheSysNameBudgetWithoutLeavingATrailingSeparator() {
        String derived = EmbeddingTypeResolver.toEmbeddingType("m".repeat(400));

        assertThat(derived).hasSize(EmbeddingTypeResolver.MAX_LENGTH);
        assertThat(derived).doesNotEndWith("-");
    }

    @Test
    void rejectsAnUnconfiguredModelRatherThanFallingBackToAPlaceholder() {
        // A silent fallback would write every document under one placeholder type, which is the
        // orphaning this class exists to prevent. Fail at startup instead.
        assertThatThrownBy(() -> EmbeddingTypeResolver.toEmbeddingType(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding.model-name");
        assertThatThrownBy(() -> EmbeddingTypeResolver.toEmbeddingType("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAModelNameThatSanitizesToNothing() {
        assertThatThrownBy(() -> EmbeddingTypeResolver.toEmbeddingType("///"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty embedding type");
    }

    @Test
    void isDeterministic_soTheWriteAndClearPathsCannotDrift() {
        // The drift guard: both paths call this method, so the only way they can disagree about a
        // child name is if the derivation is not a pure function of the configured model.
        String first = EmbeddingTypeResolver.toEmbeddingType("ai/mxbai-embed-large");
        String second = EmbeddingTypeResolver.toEmbeddingType("ai/mxbai-embed-large");

        assertThat(first).isEqualTo(second);
    }
}
