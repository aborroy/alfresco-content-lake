package org.hyland.contentlake.rag.config;

import org.hyland.contentlake.rag.service.NoOpRetrievalGrader;
import org.hyland.contentlake.rag.service.RetrievalGrader;
import org.hyland.contentlake.rag.service.ScoreThresholdRetrievalGrader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalGradingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RetrievalGradingConfig.class, RagProperties.class);

    @Test
    void gradingDisabledByDefault_registersNoOpRetrievalGrader() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RetrievalGrader.class);
            assertThat(context.getBean(RetrievalGrader.class)).isInstanceOf(NoOpRetrievalGrader.class);
        });
    }

    @Test
    void gradingEnabled_registersScoreThresholdRetrievalGrader() {
        contextRunner
                .withPropertyValues("rag.retrieval-grading.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RetrievalGrader.class);
                    assertThat(context.getBean(RetrievalGrader.class))
                            .isInstanceOf(ScoreThresholdRetrievalGrader.class);
                });
    }
}
