package org.hyland.contentlake.service;

import lombok.Data;

/**
 * Bindable form of {@link ReconcileConfig}, for the {@code *.reconcile.*} block of each batch
 * ingester's own configuration prefix.
 *
 * <p>One definition rather than three identical nested classes: core owns no configuration prefix, so
 * this carries none either. Each ingester declares a field of this type inside its own
 * {@code @ConfigurationProperties} class, which is what keeps the per-module prefix convention
 * ({@code ingestion}, {@code nuxeo.batch}, {@code filesystem.batch}) intact.</p>
 */
@Data
public class ReconcileProperties {

    /**
     * Whether a batch sync deletes indexed documents its discovery pass did not see.
     *
     * <p>Off by default. The sweep deletes documents, so an operator turns it on deliberately, and the
     * first sweep on a corpus that has accumulated drift will legitimately trip the guards below.</p>
     */
    private boolean enabled = false;

    /**
     * Largest fraction of in-scope indexed documents one sweep may delete before it aborts.
     *
     * <p>The backstop against a discovery pass that under-reports without saying so. Deliberately
     * low: on a healthy corpus a sweep deletes almost nothing, so anything above a few percent is
     * more likely a discovery problem than a genuine bulk deletion in the source.</p>
     */
    private double maxDeleteRatio = 0.10;

    /**
     * Absolute ceiling on deletions per sweep.
     *
     * <p>Separate from the ratio because on a large corpus a ratio that holds can still amount to a
     * large number of documents.</p>
     */
    private int maxDeletes = 1000;

    /**
     * Bound on the node ids retained from a discovery pass.
     *
     * <p>Roughly 200 bytes per id, so the default is about 40 MB. Above the bound the sweep deletes
     * nothing, because an incomplete record of what discovery saw would make present documents look
     * missing.</p>
     */
    private int maxSeenIds = 200_000;

    /** Documents per page when scanning the index for this source. */
    private int pageSize = 200;

    /** The immutable form the sweep takes. */
    public ReconcileConfig toConfig() {
        return new ReconcileConfig(enabled, maxDeleteRatio, maxDeletes, maxSeenIds, pageSize);
    }
}
