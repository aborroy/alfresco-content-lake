package org.hyland.contentlake.service.chunking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoiseReductionServiceTest {

    private final NoiseReductionService service = new NoiseReductionService(false);

    @Test
    void dotLeadersInProse_areStripped() {
        String text = "Chapter One .......... 5";

        String cleaned = service.clean(text);

        assertThat(cleaned).doesNotContain("..........");
    }

    @Test
    void pipeTable_survivesCleanup() {
        String text = """
                Summary of figures below.

                | Item | Value |
                | ---- | ----- |
                | A    | 1     |
                | B    | 2     |

                End of report.
                """;

        String cleaned = service.clean(text);

        // The pipe/separator characters that dot-leader and repeated-char rules would strip stay put.
        assertThat(cleaned).contains("| Item | Value |");
        assertThat(cleaned).contains("| ---- | ----- |");
        assertThat(cleaned).contains("| A    | 1     |");
        assertThat(cleaned).contains("| B    | 2     |");
    }

    @Test
    void separatorRowOutsideTable_isNotProtected() {
        // A lone rule line (not part of a 2+ line table block) is still treated as a dot-leader.
        String text = "Heading\n==========\nBody text follows here.";

        String cleaned = service.clean(text);

        assertThat(cleaned).doesNotContain("==========");
    }
}
