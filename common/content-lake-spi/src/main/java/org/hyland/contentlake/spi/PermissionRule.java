package org.hyland.contentlake.spi;

/**
 * A single, structured access-control rule on a {@link SourceNode}.
 *
 * <p>Aligned with the Open Ingestion Standard (OIS) {@code PermissionRule} shape
 * (https://github.com/OpenCrawling/open-ingestion-standard). Unlike the flat
 * {@code readPrincipals} / {@code denyPrincipals} sets that the hxpr pipeline consumes today, this
 * record can express the identity type (user vs group) and the access verb, which future non-hxpr
 * output connectors need.</p>
 *
 * @param identity     the raw authority / principal identifier as reported by the source
 * @param identityType {@code "user"} or {@code "group"}
 * @param displayName  human-readable label for the identity; may equal {@code identity}
 * @param access       the granted or denied access verb, e.g. {@code "READ"}, {@code "WRITE"},
 *                     {@code "READ_DENY"}
 */
public record PermissionRule(String identity, String identityType, String displayName, String access) {}
