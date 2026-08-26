package org.hyland.contentlake.extractor;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TikaTextExtractorTest {

    private final TikaTextExtractor extractor = new TikaTextExtractor();

    @Test
    void supports_isTrueForAnyDeclaredMimeType_andFalseForBlank() {
        assertThat(extractor.supports("application/pdf")).isTrue();
        assertThat(extractor.supports("text/plain")).isTrue();
        assertThat(extractor.supports(null)).isFalse();
        assertThat(extractor.supports("  ")).isFalse();
    }

    @Test
    void extractText_passesPlainTextThroughVerbatim() {
        String body = "Line one.\nLine two with an accent: café.";
        Resource content = new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8));

        String text = extractor.extractText(content, "text/plain");

        assertThat(text).isEqualTo(body);
    }

    @Test
    void extractText_parsesBinaryFormatViaAutoDetectParser() {
        // HTML exercises the same AutoDetectParser + BodyContentHandler path used for PDF/DOCX,
        // without requiring a checked-in binary fixture.
        String html = "<html><head><title>t</title></head><body><h1>Heading</h1>"
                + "<p>Hello Tika extraction.</p></body></html>";
        Resource content = new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8));

        String text = extractor.extractText(content, "text/html");

        assertThat(text).contains("Heading").contains("Hello Tika extraction.");
        assertThat(text).doesNotContain("<h1>").doesNotContain("<p>");
    }

    @Test
    void extractText_returnsNullForUnsupportedMimeType() {
        Resource content = new ByteArrayResource("irrelevant".getBytes(StandardCharsets.UTF_8));

        assertThat(extractor.extractText(content, null)).isNull();
    }
}
