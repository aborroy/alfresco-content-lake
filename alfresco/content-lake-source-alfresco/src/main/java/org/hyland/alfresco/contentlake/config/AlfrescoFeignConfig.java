package org.hyland.alfresco.contentlake.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import feign.Feign;
import feign.RequestInterceptor;
import feign.Response;
import feign.Util;
import feign.auth.BasicAuthRequestInterceptor;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.core.handler.NodesApi;
import org.alfresco.discovery.handler.DiscoveryApi;
import org.alfresco.search.handler.SearchApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * Builds the Alfresco ACS REST handlers ({@link NodesApi}, {@link DiscoveryApi}, {@link SearchApi})
 * as plain <a href="https://github.com/OpenFeign/feign">OpenFeign</a> clients, replacing the ACS
 * SDK starter's {@code @EnableFeignClients} wiring.
 *
 * <p><strong>Why this exists (issue #77):</strong> the ACS
 * {@code alfresco-acs-java-rest-api-spring-boot-starter} pulls in {@code spring-cloud-openfeign
 * 4.2.1}, which targets Spring Boot 3.5. On Spring Boot 4 its auto-configuration fails at context
 * refresh with {@code NoClassDefFoundError} for Boot classes that were relocated/removed
 * ({@code web.ServerProperties}, {@code web.context.WebServerInitializedEvent},
 * {@code data.web.SpringDataWebProperties}). The failure lands inside {@code FeignAutoConfiguration}
 * itself, so it cannot be worked around by excluding auto-configs while still using
 * {@code @EnableFeignClients}. No Boot-4-compatible Spring Cloud release is available yet.</p>
 *
 * <p>The fix builds the handlers directly with {@link Feign.Builder}, reusing two Boot-independent
 * pieces from {@code spring-cloud-openfeign-core} — {@link SpringMvcContract} (the ACS handler
 * interfaces are annotated with Spring MVC annotations) and {@link ResponseEntityDecoder} (they
 * return {@link org.springframework.http.ResponseEntity}) — over Boot-independent
 * {@code feign-jackson} codecs. This removes {@code spring-cloud-openfeign} from the runtime
 * auto-configuration path entirely; the ingesters additionally exclude the spring-cloud
 * auto-configurations (see their {@code application.yml}).</p>
 *
 * <p>Each ACS handler lives under a distinct base path (the SDK expressed these as the
 * {@code @FeignClient(url=..., path=...)} prefix). Since the SDK auto-configuration is disabled,
 * those path defaults are replicated here as {@code @Value} defaults (overridable via the same
 * {@code *.service.path} properties the SDK used).</p>
 *
 * <p>Basic authentication mirrors the SDK's {@code BasicAuthConfiguration}: a
 * {@link BasicAuthRequestInterceptor} built from
 * {@code content.service.security.basicAuth.username/password}.</p>
 */
@Slf4j
@Configuration
public class AlfrescoFeignConfig {

    /**
     * Jackson {@link ObjectMapper} for the ACS REST models. Lenient on unknown properties so the
     * client keeps working when Alfresco adds response fields.
     *
     * <p>Deliberately NOT a Spring bean: exposing a second {@code ObjectMapper} bean would make
     * generic {@code ObjectMapper} injection ambiguous (core already provides
     * {@code contentLakeObjectMapper}). It is used only to build the Feign codecs below.</p>
     */
    // The ACS models expose java.time types (e.g. OffsetDateTime on Node.createdAt/modifiedAt).
    // Without the JSR-310 module, decoding ANY node throws InvalidDefinitionException, which callers
    // swallow as "node not found" -> zero discovery/ingestion (issue #78). Register JavaTimeModule
    // explicitly rather than findAndRegisterModules(), which would also pull in the JAXB annotation
    // module and fail with NoClassDefFoundError (javax.xml.bind is not on the classpath).
    private static final ObjectMapper FEIGN_OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule());

    private final String serviceUrl;
    private final String contentPath;
    private final String discoveryPath;
    private final String searchPath;
    private final String basicAuthUsername;
    private final String basicAuthPassword;

    public AlfrescoFeignConfig(
            @Value("${content.service.url}") String serviceUrl,
            // Defaults mirror the ACS SDK's alfresco-java-rest-api-default.properties.
            @Value("${content.service.path:/alfresco/api/-default-/public/alfresco/versions/1}") String contentPath,
            @Value("${discovery.service.path:/alfresco/api}") String discoveryPath,
            @Value("${search.service.path:/alfresco/api/-default-/public/search/versions/1}") String searchPath,
            @Value("${content.service.security.basicAuth.username:}") String basicAuthUsername,
            @Value("${content.service.security.basicAuth.password:}") String basicAuthPassword) {
        this.serviceUrl = stripTrailingSlash(serviceUrl);
        this.contentPath = contentPath;
        this.discoveryPath = discoveryPath;
        this.searchPath = searchPath;
        this.basicAuthUsername = basicAuthUsername;
        this.basicAuthPassword = basicAuthPassword;
    }

    @Bean
    public Encoder alfrescoFeignEncoder() {
        return new JacksonEncoder(FEIGN_OBJECT_MAPPER);
    }

    @Bean
    public Decoder alfrescoFeignDecoder() {
        // ResponseEntityDecoder unwraps the ResponseEntity<T> return types used by the ACS handlers.
        // The inner ContentAwareDecoder returns raw bytes for binary endpoints (getNodeContent
        // returns ResponseEntity<Resource>, i.e. the file body, NOT JSON) and delegates everything
        // else to Jackson. Without this, JacksonDecoder tries to parse plain-text content as JSON
        // and fails with "Unrecognized token", which aborts text extraction -> no chunks/embeddings
        // are ever produced (issue #79).
        return new ResponseEntityDecoder(new ContentAwareDecoder(new JacksonDecoder(FEIGN_OBJECT_MAPPER)));
    }

    /** Basic-auth interceptor mirroring the ACS SDK's BasicAuthConfiguration. */
    @Bean
    public RequestInterceptor alfrescoBasicAuthRequestInterceptor() {
        return new BasicAuthRequestInterceptor(
                basicAuthUsername != null ? basicAuthUsername : "",
                basicAuthPassword != null ? basicAuthPassword : "");
    }

    @Bean
    public NodesApi nodesApi(Encoder alfrescoFeignEncoder,
                             Decoder alfrescoFeignDecoder,
                             RequestInterceptor alfrescoBasicAuthRequestInterceptor) {
        return build(NodesApi.class, contentPath, alfrescoFeignEncoder, alfrescoFeignDecoder,
                alfrescoBasicAuthRequestInterceptor);
    }

    @Bean
    public DiscoveryApi discoveryApi(Encoder alfrescoFeignEncoder,
                                     Decoder alfrescoFeignDecoder,
                                     RequestInterceptor alfrescoBasicAuthRequestInterceptor) {
        return build(DiscoveryApi.class, discoveryPath, alfrescoFeignEncoder, alfrescoFeignDecoder,
                alfrescoBasicAuthRequestInterceptor);
    }

    @Bean
    public SearchApi searchApi(Encoder alfrescoFeignEncoder,
                               Decoder alfrescoFeignDecoder,
                               RequestInterceptor alfrescoBasicAuthRequestInterceptor) {
        return build(SearchApi.class, searchPath, alfrescoFeignEncoder, alfrescoFeignDecoder,
                alfrescoBasicAuthRequestInterceptor);
    }

    private <T> T build(Class<T> apiType, String path, Encoder encoder, Decoder decoder,
                        RequestInterceptor authInterceptor) {
        String target = serviceUrl + path;
        log.info("Building ACS {} Feign client against {}", apiType.getSimpleName(), target);
        return Feign.builder()
                .contract(new SpringMvcContract())
                .encoder(encoder)
                .decoder(decoder)
                .requestInterceptor(authInterceptor)
                .target(apiType, target);
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Feign {@link Decoder} that returns the raw response body for binary/content endpoints
     * (return type {@link Resource}, {@link InputStream}, or {@code byte[]} such as
     * {@code NodesApi.getNodeContent}) and delegates all other (JSON) types to the wrapped Jackson
     * decoder. This mirrors what the spring-cloud SpringDecoder + HttpMessageConverters chain did
     * before it was replaced (issue #77) and prevents JacksonDecoder from trying to parse binary
     * content as JSON (issue #79).
     */
    static final class ContentAwareDecoder implements Decoder {

        private final Decoder delegate;

        ContentAwareDecoder(Decoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object decode(Response response, Type type) throws IOException {
            byte[] body = readBody(response);
            if (type == Resource.class) {
                return body != null ? new ByteArrayResource(body) : new InputStreamResource(InputStream.nullInputStream());
            }
            if (type == InputStream.class) {
                return new ByteArrayInputStream(body != null ? body : new byte[0]);
            }
            if (type == byte[].class) {
                return body != null ? body : new byte[0];
            }
            // Re-wrap the already-read bytes so the delegate can consume the body stream.
            Response buffered = response.toBuilder()
                    .body(body != null ? body : new byte[0])
                    .build();
            return delegate.decode(buffered, type);
        }

        private static byte[] readBody(Response response) throws IOException {
            if (response.body() == null) {
                return null;
            }
            try (InputStream in = response.body().asInputStream()) {
                return Util.toByteArray(in);
            }
        }
    }
}
