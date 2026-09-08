package org.hyland.contentlake.service;

/**
 * Tunables for a reconciliation sweep.
 *
 * <p>A plain record rather than a {@code @ConfigurationProperties} class because content-lake-core
 * owns no configuration prefix and the three batch ingesters have three of their own
 * ({@code ingestion}, {@code nuxeo.batch}, {@code filesystem.batch}). Each ingester binds its own
 * nested properties class and maps it to this record, so the per-module prefix convention holds.</p>
 *
 * @param enabled        whether the sweep runs at all. Off by default: a sweep deletes documents, so
 *                       an operator turns it on deliberately after reading what the guards do.
 * @param maxDeleteRatio the largest fraction of in-scope indexed documents the sweep may delete
 *                       before aborting. The backstop against a discovery pass that under-reports
 *                       without saying so.
 * @param maxDeletes     absolute ceiling on deletions per sweep, so a large corpus cannot lose a
 *                       large number of documents just because the ratio held.
 * @param maxSeenIds     bound on the node ids retained from discovery; see {@link SeenSet}
 * @param pageSize       documents per page when scanning the index
 */
public record ReconcileConfig(
        boolean enabled,
        double maxDeleteRatio,
        int maxDeletes,
        int maxSeenIds,
        int pageSize
) {

    /** The sweep off, which is what every ingester defaults to. */
    public static ReconcileConfig disabled() {
        return new ReconcileConfig(false, 0.10, 1000, 200_000, 200);
    }
}
