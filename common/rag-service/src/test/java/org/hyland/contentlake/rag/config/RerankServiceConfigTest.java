package org.hyland.contentlake.rag.config;

import org.hyland.contentlake.rag.service.NoOpRerankService;
import org.hyland.contentlake.rag.service.RerankService;
import org.hyland.contentlake.rag.service.TeiCrossEncoderRerankService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RerankServiceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RerankServiceConfig.class, RagProperties.class);

    @Test
    void withoutRerankerUrl_registersNoOpRerankService() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RerankService.class);
            assertThat(context.getBean(RerankService.class)).isInstanceOf(NoOpRerankService.class);
        });
    }

    @Test
    void withBlankRerankerUrl_registersNoOpRerankService() {
        contextRunner
                .withPropertyValues("rag.reranker.url=")
                .run(context -> {
                    assertThat(context).hasSingleBean(RerankService.class);
                    assertThat(context.getBean(RerankService.class)).isInstanceOf(NoOpRerankService.class);
                });
    }

    @Test
    void withRerankerUrl_registersTeiCrossEncoderRerankService() {
        contextRunner
                .withPropertyValues("rag.reranker.url=http://reranker:8081")
                .run(context -> {
                    assertThat(context).hasSingleBean(RerankService.class);
                    assertThat(context.getBean(RerankService.class))
                            .isInstanceOf(TeiCrossEncoderRerankService.class);
                });
    }

    @Test
    void withRerankerUrl_exactlyOneBeanRegistered() {
        contextRunner
                .withPropertyValues("rag.reranker.url=http://reranker:8081")
                .run(context -> assertThat(context).hasSingleBean(RerankService.class));
    }
}
