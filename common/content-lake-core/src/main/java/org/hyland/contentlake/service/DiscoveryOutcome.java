package org.hyland.contentlake.service;

import java.util.List;

/**
 * What a discovery pass is willing to assert about its own completeness.
 *
 * <p>A reconciliation sweep may only delete when discovery enumerated its whole scope, so that claim
 * has to come from discovery itself rather than being inferred from the absence of a thrown
 * exception. Several discovery paths return a partial or empty result and log a warning instead of
 * failing: an Alfresco search-index retry that exhausts its attempts, a configured root path that
 * does not resolve, a paging loop that ends on a short page while the source still reports more. Each
 * of those is indistinguishable, from the caller's side, from a genuinely small scope.</p>
 *
 * @param complete          whether every configured root was enumerated in full
 * @param resolvedRootPaths the source paths discovery actually covered, which bound the sweep's scope
 * @param reasons           why the pass is incomplete; empty when it is complete
 */
public record DiscoveryOutcome(boolean complete, List<String> resolvedRootPaths, List<String> reasons) {

    public DiscoveryOutcome {
        resolvedRootPaths = resolvedRootPaths == null ? List.of() : List.copyOf(resolvedRootPaths);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** A pass that enumerated every configured root in full. */
    public static DiscoveryOutcome complete(List<String> resolvedRootPaths) {
        return new DiscoveryOutcome(true, resolvedRootPaths, List.of());
    }

    /** A pass that did not, and therefore must not drive any deletion. */
    public static DiscoveryOutcome incomplete(List<String> resolvedRootPaths, List<String> reasons) {
        return new DiscoveryOutcome(false, resolvedRootPaths, reasons);
    }

    /** The reasons joined for a log line, or an empty string when the pass is complete. */
    public String reasonSummary() {
        return String.join("; ", reasons);
    }
}
