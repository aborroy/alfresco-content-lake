package org.hyland.alfresco.contentlake.batch.config;

import org.hyland.contentlake.config.HxprProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the outbound engine auth interceptor sets HTTP Basic credentials and the
 * {@code HXCS-REPOSITORY} header (ai-ready-index community engine, Basic-auth mode).
 */
class AppConfigTest {

    @Test
    void interceptorSetsBasicAuthAndRepositoryHeader() throws IOException {
        HxprProperties props = new HxprProperties();
        props.setUsername("alice");
        props.setPassword("s3cret");
        props.setRepositoryId("repo-1");

        ClientHttpRequestInterceptor interceptor = AppConfig.hxprAuthInterceptor(props);

        HttpHeaders headers = new HttpHeaders();
        HttpRequest request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(mock(ClientHttpResponse.class));

        interceptor.intercept(request, new byte[0], execution);

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Basic " + HttpHeaders.encodeBasicAuth("alice", "s3cret", null));
        assertThat(headers.getFirst(AppConfig.HXCS_REPOSITORY)).isEqualTo("repo-1");
    }
}
