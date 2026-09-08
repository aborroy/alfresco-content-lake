package org.hyland.contentlake.service;

/**
 * Derives the hxpr embedding type from the configured embedding model.
 *
 * <p>The embedding type is the discriminator hxpr indexes vectors by: embedding children are named
 * {@code _e_{embeddingType}} and the embeddings query accepts an {@code embeddingType} parameter
 * that defaults to the {@code *} wildcard. Because the type ends up inside a {@code sys_name}, a
 * configured model such as {@code ai/mxbai-embed-large} cannot be used verbatim, so it is sanitized
 * here.</p>
 *
 * <p>This is the single derivation used by both the write and the clear path in
 * {@code HxprService}. Keeping it in one place is the point: when the two disagree, children written
 * under one name can no longer be named by the other, they survive a re-sync, and stale vectors from
 * a retired model keep answering queries through the wildcard.</p>
 */
public final class EmbeddingTypeResolver {

    /**
     * Upper bound on a derived type. hxpr caps {@code sys_name} at 256 characters and the name
     * carries a {@code _e_} prefix, so this leaves ample headroom.
     */
    public static final int MAX_LENGTH = 200;

    private EmbeddingTypeResolver() {
    }

    /**
     * Sanitizes a configured embedding model into an embedding type usable inside a
     * {@code sys_name}: lowercased, with every character outside {@code [a-z0-9._-]} replaced by
     * {@code -}, runs collapsed, edges trimmed, and the result truncated to {@link #MAX_LENGTH}.
     *
     * <p>{@code ai/mxbai-embed-large} becomes {@code ai-mxbai-embed-large}.</p>
     *
     * @param configuredModel the configured embedding model name
     * @return the derived embedding type
     * @throws IllegalArgumentException when {@code configuredModel} is null, blank, or sanitizes to
     *         nothing. Failing at startup is deliberate: a silent fallback would write every
     *         document under one placeholder type and reintroduce the orphaning this class prevents.
     */
    public static String toEmbeddingType(String configuredModel) {
        if (configuredModel == null || configuredModel.isBlank()) {
            throw new IllegalArgumentException(
                    "Embedding model is not configured; cannot derive an embedding type. "
                    + "Set embedding.model-name (or spring.ai.openai.embedding.model).");
        }

        StringBuilder sanitized = new StringBuilder(configuredModel.length());
        for (char c : configuredModel.toLowerCase().toCharArray()) {
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (allowed) {
                sanitized.append(c);
            } else if (!sanitized.isEmpty() && sanitized.charAt(sanitized.length() - 1) != '-') {
                sanitized.append('-');
            }
        }

        while (!sanitized.isEmpty() && sanitized.charAt(sanitized.length() - 1) == '-') {
            sanitized.setLength(sanitized.length() - 1);
        }

        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Embedding model '" + configuredModel + "' sanitizes to an empty embedding type.");
        }

        if (sanitized.length() > MAX_LENGTH) {
            sanitized.setLength(MAX_LENGTH);
            while (!sanitized.isEmpty() && sanitized.charAt(sanitized.length() - 1) == '-') {
                sanitized.setLength(sanitized.length() - 1);
            }
        }

        return sanitized.toString();
    }
}
