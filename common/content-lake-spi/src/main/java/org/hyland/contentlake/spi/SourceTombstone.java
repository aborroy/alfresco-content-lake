package org.hyland.contentlake.spi;

import java.time.OffsetDateTime;

/**
 * Source-agnostic record of a node that should no longer be indexed.
 *
 * <p>Deliberately not a {@link SourceNode}. By the time most tombstones are raised the node is gone
 * from its source, so there is no name, mime type, path or ACL to carry: a removal event supplies a
 * node id and a timestamp and nothing else. Forcing those cases to fabricate a {@code SourceNode} of
 * mostly nulls would break that record's implied invariants for no gain.</p>
 *
 * <p>{@code sourceId} is not carried either: the pipeline resolves it from its
 * {@link ContentSourceClient}, so a tombstone cannot name a node in a source the pipeline does not
 * own.</p>
 *
 * @param nodeId    source-system node identifier
 * @param deletedAt timestamp of the event that removed it, or {@code null} for an unconditional
 *                  delete. When present it is compared against the stored {@code source_modifiedAt}
 *                  so an event that arrives after a newer sync does not undo it.
 * @param reason    why the node is being removed; carried for the log and the reconciliation report
 */
public record SourceTombstone(String nodeId, OffsetDateTime deletedAt, Reason reason) {

    /** Why a tombstone was raised. */
    public enum Reason {
        /** The source reported the node deleted or trashed. */
        DELETED,
        /** The node still exists but left Content Lake scope: aspect removed, ACL or exclusion change. */
        OUT_OF_SCOPE,
        /** A discovery pass that completed successfully did not see it. */
        MISSING_AT_RECONCILE
    }

    /** A tombstone for a node the source reported deleted, with no event timestamp. */
    public static SourceTombstone deleted(String nodeId) {
        return new SourceTombstone(nodeId, null, Reason.DELETED);
    }

    /** A tombstone for a node a reconciliation sweep did not see in the source. */
    public static SourceTombstone missingAtReconcile(String nodeId) {
        return new SourceTombstone(nodeId, null, Reason.MISSING_AT_RECONCILE);
    }
}
