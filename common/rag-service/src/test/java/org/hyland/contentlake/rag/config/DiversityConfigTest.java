package org.hyland.contentlake.rag.config;

import org.hyland.contentlake.rag.service.DiversitySelector;
import org.hyland.contentlake.rag.service.MmrSelector;
import org.hyland.contentlake.rag.service.NoOpDiversitySelector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DiversityConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DiversityConfig.class, RagProperties.class);

    @Test
    void mmrDisabledByDefault_registersNoOpDiversitySelector() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DiversitySelector.class);
            assertThat(context.getBean(DiversitySelector.class)).isInstanceOf(NoOpDiversitySelector.class);
        });
    }

    @Test
    void mmrEnabled_registersMmrSelector() {
        contextRunner
                .withPropertyValues("rag.mmr.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(DiversitySelector.class);
                    assertThat(context.getBean(DiversitySelector.class)).isInstanceOf(MmrSelector.class);
                });
    }
}
