package org.alfresco.contentlake.spi;

import org.springframework.core.io.Resource;

/**
 * Extracts plain text from a binary document resource.
 *
 * <p>Alfresco uses Transform Core AIO; Nuxeo uses embedded Apache Tika.
 * The shared sync pipeline delegates to this interface and has no knowledge
 * of the underlying transform mechanism.</p>
 */
public interface TextExtractor {

    /**
     * Returns {@code true} when this extractor can process the given MIME type.
     *
     * @param mimeType source MIME type to check
     * @return {@code true} if extraction is supported
     */
    boolean supports(String mimeType);

    /**
     * Extracts plain text from the content resource.
     *
     * @param content  resource containing the binary content
     * @param mimeType MIME type of the source content
     * @return extracted plain text, or {@code null} when no text can be produced
     */
    String extractText(Resource content, String mimeType);
}
