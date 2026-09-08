package org.hyland.contentlake.service;

import java.util.HashSet;
import java.util.Set;

/**
 * Bounded record of the node ids a discovery pass saw, used by a reconciliation sweep to decide
 * which indexed documents the source no longer has.
 *
 * <p>The bound exists because a full discovery of a large corpus can be hundreds of thousands of
 * ids, and this set is held for the duration of a sync. Once the bound is passed the set stops
 * growing and reports {@link #overflowed()}, and the sweep must then delete nothing.</p>
 *
 * <p>Refusing to sweep is the correct degradation rather than approximating. The only cheap
 * sub-linear alternative is a probabilistic set, whose error mode here is a false negative:
 * "discovery did not see this id" for an id it did see, which means deleting a document that still
 * exists in the source. There is no cheap approximation without that error mode, so the set is
 * exact and bounded, and an overflow aborts loudly.</p>
 *
 * <p>Not thread-safe: a discovery pass populates it from one thread before the sweep reads it.</p>
 */
public final class SeenSet {

    private final Set<String> ids;
    private final int maxIds;
    private boolean overflowed;

    /**
     * @param maxIds the most ids to retain; a non-positive value disables retention entirely, which
     *               reports an overflow on the first add
     */
    public SeenSet(int maxIds) {
        this.maxIds = maxIds;
        this.ids = new HashSet<>(Math.clamp(maxIds / 2, 16, 1 << 16));
    }

    /** Records an id, or flags an overflow when the bound has been reached. */
    public void add(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        if (ids.size() >= maxIds) {
            overflowed = true;
            return;
        }
        ids.add(nodeId);
    }

    public boolean contains(String nodeId) {
        return nodeId != null && ids.contains(nodeId);
    }

    /**
     * Whether the bound was reached, in which case the set is incomplete and no deletion decision
     * can be based on it.
     */
    public boolean overflowed() {
        return overflowed;
    }

    public int size() {
        return ids.size();
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }
}
