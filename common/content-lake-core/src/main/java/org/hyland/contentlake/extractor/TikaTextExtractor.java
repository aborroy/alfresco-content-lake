package org.hyland.contentlake.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.hyland.contentlake.spi.TextExtractor;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Source-agnostic {@link TextExtractor} backed by Apache Tika.
 *
 * <p>Unlike {@code TransformClient} (Alfresco Transform Core AIO) and {@code NuxeoConversionClient}
 * (Nuxeo ConversionService), this extractor has no runtime dependency on a source system. It is the
 * fallback that lets content sources without a server-side transform pipeline - such as the
 * filesystem connector - still produce plain text.</p>
 *
 * <p>{@code text/plain} content is passed through directly; everything else is parsed by Tika's
 * {@link AutoDetectParser}. Any parse failure degrades to {@code null} (no text) rather than
 * propagating, matching how {@code TransformClient} degrades on an unsupported transform.</p>
 *
 * <p>This is a plain class, not a Spring component: {@code content-lake-core} is shared by every
 * ingester and by {@code rag-service}, so it must not auto-register a {@link TextExtractor}. Wire it
 * where a source-agnostic extractor is wanted, guarding with
 * {@code @ConditionalOnMissingBean(TextExtractor.class)} so a source-specific extractor takes
 * precedence when present.</p>
 */
@Slf4j
public class TikaTextExtractor implements TextExtractor {

    private static final String TEXT_PLAIN = "text/plain";

    private final Parser parser = new AutoDetectParser();

    /** Tika can attempt extraction for any declared MIME type; unknown/blank types are unsupported. */
    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && !mimeType.isBlank();
    }

    @Override
    public String extractText(Resource content, String mimeType) {
        if (!supports(mimeType)) {
            return null;
        }
        try (InputStream in = content.getInputStream()) {
            if (isPlainText(mimeType)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // -1 disables Tika's default 100k-character write limit so full documents are extracted.
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(Metadata.CONTENT_TYPE, mimeType);
            parser.parse(in, handler, metadata, new ParseContext());
            String text = handler.toString();
            return text == null || text.isBlank() ? null : text.trim();
        } catch (Exception e) {
            log.warn("Tika text extraction failed for mimeType={}: {}", mimeType, e.getMessage());
            return null;
        }
    }

    private static boolean isPlainText(String mimeType) {
        return TEXT_PLAIN.equalsIgnoreCase(mimeType) || mimeType.toLowerCase().startsWith("text/plain");
    }
}
