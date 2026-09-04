package org.hyland.contentlake.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GroupResolutionFailurePolicy")
class GroupResolutionFailurePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"fail-closed", "FAIL-CLOSED", "fail_closed", "  fail-closed  "})
    void parsesTheFailClosedForms(String value) {
        assertThat(GroupResolutionFailurePolicy.parse(value))
                .isEqualTo(GroupResolutionFailurePolicy.FAIL_CLOSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"degrade", "DEGRADE", " Degrade "})
    void parsesTheDegradeForms(String value) {
        assertThat(GroupResolutionFailurePolicy.parse(value))
                .isEqualTo(GroupResolutionFailurePolicy.DEGRADE);
    }

    @Test
    void defaultsToFailClosedWhenUnset() {
        assertThat(GroupResolutionFailurePolicy.parse(null))
                .isEqualTo(GroupResolutionFailurePolicy.FAIL_CLOSED);
        assertThat(GroupResolutionFailurePolicy.parse(""))
                .isEqualTo(GroupResolutionFailurePolicy.FAIL_CLOSED);
    }

    @Test
    void aTypoDoesNotWidenAccess() {
        // Anything unrecognised has to land on the safe side, not on the permissive one.
        assertThat(GroupResolutionFailurePolicy.parse("degraded"))
                .isEqualTo(GroupResolutionFailurePolicy.FAIL_CLOSED);
        assertThat(GroupResolutionFailurePolicy.parse("open"))
                .isEqualTo(GroupResolutionFailurePolicy.FAIL_CLOSED);
    }
}
