package org.hyland.contentlake.rag.controller;

import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprTermsAggregationResult;
import org.hyland.contentlake.rag.health.HxprHealthIndicator;
import org.hyland.contentlake.rag.health.ModelRunnerHealthIndicator;
import org.hyland.contentlake.rag.model.StatusResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatusControllerTest {

    @Mock
    private HxprService hxprService;
    @Mock
    private HxprHealthIndicator hxprHealthIndicator;
    @Mock
    private ModelRunnerHealthIndicator modelRunnerHealthIndicator;

    @InjectMocks
    private StatusController controller;

    @Test
    void status_aggregatesPerSourceCountsAndTotal_whenHxprUp() {
        when(hxprHealthIndicator.health()).thenReturn(Health.up().build());
        when(modelRunnerHealthIndicator.health())
                .thenReturn(Health.up().withDetail("url", "http://mr:12434/v1/models").build());

        HxprTermsAggregationResult agg = new HxprTermsAggregationResult();
        agg.setAggregationsBuckets(List.of(
                bucket("alfresco:repo-1", 7),
                bucket("nuxeo:prod", 3)));
        when(hxprService.termsAggregation(isNull(), eq("cin_sourceId"), isNull(), anyInt()))
                .thenReturn(agg);

        StatusResponse response = controller.status();

        assertThat(response.getHxprStatus()).isEqualTo("UP");
        assertThat(response.getTotalDocuments()).isEqualTo(10);
        assertThat(response.getSourceCounts())
                .containsEntry("alfresco:repo-1", 7L)
                .containsEntry("nuxeo:prod", 3L);
        assertThat(response.getEmbeddingModel().getStatus()).isEqualTo("UP");
        assertThat(response.getEmbeddingModel().getUrl()).isEqualTo("http://mr:12434/v1/models");
    }

    @Test
    void status_skipsAggregationAndReportsDown_whenHxprDown() {
        when(hxprHealthIndicator.health()).thenReturn(Health.down().build());
        when(modelRunnerHealthIndicator.health()).thenReturn(Health.down().build());

        StatusResponse response = controller.status();

        assertThat(response.getHxprStatus()).isEqualTo("DOWN");
        assertThat(response.getTotalDocuments()).isZero();
        assertThat(response.getSourceCounts()).isEmpty();
        verify(hxprService, never()).termsAggregation(any(), any(), any(), anyInt());
    }

    private static HxprTermsAggregationResult.Bucket bucket(String key, long count) {
        HxprTermsAggregationResult.Bucket b = new HxprTermsAggregationResult.Bucket();
        b.setKey(key);
        b.setDocCount(count);
        return b;
    }
}
