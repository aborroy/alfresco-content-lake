package org.hyland.alfresco.contentlake.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.alfresco.contentlake.batch.config.IngestionProperties;
import org.hyland.alfresco.contentlake.batch.model.BatchSyncRequest;
import org.hyland.alfresco.contentlake.client.AlfrescoClient;
import org.hyland.alfresco.contentlake.client.AlfrescoSearchService;
import org.hyland.alfresco.contentlake.service.ContentLakeScopeResolver;
import org.hyland.contentlake.service.DiscoveryOutcome;
import org.alfresco.core.model.Node;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers candidate Alfresco nodes for ingestion based on request parameters or configuration.
 *
 * <p>Before traversing each root folder, this service ensures the folder has the
 * {@code cl:indexed} aspect. If the aspect is missing it is added automatically, making
 * the batch request itself the act of onboarding a folder into Content Lake.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NodeDiscoveryService {

    private final AlfrescoClient alfrescoClient;
    private final AlfrescoSearchService searchService;
    private final IngestionProperties props;
    private final ContentLakeScopeResolver scopeResolver;

    /**
     * A discovery pass and its own account of whether it enumerated its whole scope.
     *
     * <p>The two travel together because the stream is lazy: the silent-partial cases below only
     * become known as it is consumed, so the tally must be read <em>after</em> the stream is drained.
     * A caller that reads it earlier sees an incomplete tally, which is the safe direction.</p>
     *
     * @param nodes the discovered nodes, lazily evaluated
     * @param tally populated as {@code nodes} is consumed; call {@link DiscoveryTally#toOutcome()}
     *              only once the stream is exhausted
     */
    public record Discovery(Stream<Node> nodes, DiscoveryTally tally) {
    }

    /**
     * Records, as a lazy discovery stream is consumed, whether it covered its whole scope.
     *
     * <p>Discovery has two silent-partial paths that log a warning and continue rather than failing:
     * a root that does not resolve to a folder, and a search-index retry that exhausts its attempts.
     * Neither is distinguishable by the caller from a genuinely small scope, and both would let a
     * reconciliation sweep delete documents that still exist.</p>
     */
    public static final class DiscoveryTally {
        private final List<String> resolvedRootPaths = new ArrayList<>();
        private final List<String> reasons = new ArrayList<>();

        void addRoot(String path) {
            if (path != null && !path.isBlank()) {
                resolvedRootPaths.add(path);
            }
        }

        void rootUnresolved(String folderId, String why) {
            reasons.add("root '" + folderId + "' " + why);
        }

        void emptyAfterRetries(String folderId, int attempts) {
            // A genuinely empty folder and a lagging search index are indistinguishable here, and the
            // deletion-unsafe reading has to win.
            reasons.add("folder '" + folderId + "' returned 0 descendants after " + attempts
                    + " attempt(s); cannot tell an empty folder from a lagging index");
        }

        /** Discovery's completeness claim. Read after the stream has been drained. */
        public DiscoveryOutcome toOutcome() {
            return reasons.isEmpty()
                    ? DiscoveryOutcome.complete(resolvedRootPaths)
                    : DiscoveryOutcome.incomplete(resolvedRootPaths, reasons);
        }

        /** The source paths discovery covered, which bound a sweep's scope. */
        public List<String> resolvedRootPaths() {
            return List.copyOf(resolvedRootPaths);
        }
    }

    /**
     * Discovers nodes from the folders specified in the request.
     *
     * @param request discovery configuration
     * @return stream of nodes matching type and exclusion rules
     */
    public Stream<Node> discoverNodes(BatchSyncRequest request) {
        return discoverNodesTallied(request).nodes();
    }

    /** As {@link #discoverNodes}, but also reporting whether the pass covered its whole scope. */
    public Discovery discoverNodesTallied(BatchSyncRequest request) {
        List<String> folders = request.getFolders();
        boolean recursive = request.isRecursive();
        List<String> types = request.getTypes();
        DiscoveryTally tally = new DiscoveryTally();

        Stream<Node> nodes = folders.stream()
                .map(folderId -> ensureIndexedAndResolve(folderId, tally))
                .filter(folder -> folder != null)
                .flatMap(folder -> discoverFromFolder(folder.getId(), recursive, types, tally));

        return new Discovery(nodes, tally);
    }

    /**
     * Discovers nodes using configured sources.
     *
     * @return stream of nodes matching source filters and exclusion rules
     */
    public Stream<Node> discoverFromConfig() {
        return discoverFromConfigTallied().nodes();
    }

    /** As {@link #discoverFromConfig}, but also reporting whether the pass covered its whole scope. */
    public Discovery discoverFromConfigTallied() {
        DiscoveryTally tally = new DiscoveryTally();

        Stream<Node> nodes = props.getSources().stream()
                .flatMap(source -> {
                    Node folder = ensureIndexedAndResolve(source.getFolder(), tally);
                    if (folder == null) {
                        return Stream.empty();
                    }
                    return discoverFromFolder(folder.getId(), source.isRecursive(), source.getTypes(), tally);
                });

        return new Discovery(nodes, tally);
    }

    /**
     * Fetches the folder, ensures it has {@code cl:indexed}, and returns the resolved
     * {@link Node} with its canonical UUID. Accepts Alfresco aliases such as {@code -root-}.
     */
    private Node ensureIndexedAndResolve(String folderId, DiscoveryTally tally) {
        Node folder = alfrescoClient.getAlfrescoNode(folderId);
        if (folder == null) {
            log.warn("Folder not found, skipping: {}", folderId);
            tally.rootUnresolved(folderId, "was not found");
            return null;
        }
        if (!Boolean.TRUE.equals(folder.isIsFolder())) {
            log.warn("Node {} is not a folder, skipping", folderId);
            tally.rootUnresolved(folderId, "is not a folder");
            return null;
        }

        tally.addRoot(rootPathOf(folder));

        List<String> aspects = folder.getAspectNames() != null
                ? new ArrayList<>(folder.getAspectNames())
                : new ArrayList<>();

        if (!aspects.contains(ContentLakeScopeResolver.INDEXED_ASPECT)) {
            aspects.add(ContentLakeScopeResolver.INDEXED_ASPECT);
            alfrescoClient.updateNode(folder.getId(), aspects, null);
            scopeResolver.invalidateFolderScope(folder.getId());
            log.info("Added cl:indexed to folder {}", folder.getId());
        }

        return folder;
    }

    /**
     * The folder's own absolute path, for use as a reconciliation scope prefix.
     *
     * <p>Alfresco's {@code path.name} is the node's <em>ancestor</em> chain and excludes the node
     * itself, so a folder's own name has to be appended. Using {@code path.name} directly yields the
     * folder's parent, which as a scope prefix silently widens the sweep to every sibling: a sync of
     * one folder would consider the whole parent subtree out of scope for its own discovery pass and
     * propose deleting all of it.</p>
     *
     * @return the folder's absolute path, or {@code null} when Alfresco did not return one
     */
    private static String rootPathOf(Node folder) {
        if (folder.getPath() == null || folder.getPath().getName() == null) {
            return null;
        }
        String parentPath = folder.getPath().getName();
        String name = folder.getName();
        if (name == null || name.isBlank()) {
            return parentPath;
        }
        return parentPath.endsWith("/") ? parentPath + name : parentPath + "/" + name;
    }

    private Stream<Node> discoverFromFolder(String folderId, boolean recursive, List<String> types,
                                            DiscoveryTally tally) {
        log.info("Discovering nodes from folder: {}, recursive: {}", folderId, recursive);

        if (recursive) {
            List<Node> nodes = findDescendantFilesWithRetry(folderId, tally);
            return nodes.stream()
                    .filter(node -> matchesType(node, types))
                    .filter(node -> !matchesExcludedPath(node))
                    // The AFTS descendant query filters cl:excludeFromLake via Solr, which races the
                    // commit of a just-set exclusion (issue #81, same class as #78). Re-check each
                    // discovered node authoritatively via the Nodes REST API so a subtree excluded
                    // immediately before sync is not ingested.
                    .filter(node -> !scopeResolver.isExcludedBySelfOrAncestor(node));
        }

        // Non-recursive: single level only, no AFTS needed
        return alfrescoClient.getAllChildren(folderId).stream()
                .filter(node -> Boolean.FALSE.equals(node.isIsFolder()))
                .filter(node -> matchesType(node, types))
                .filter(node -> scopeResolver.isInScope(node));
    }

    /**
     * Runs the recursive AFTS {@code ANCESTOR:} descendant query, retrying while it returns empty
     * to absorb the Solr commit lag on the {@code ANCESTOR} relationship and a just-added
     * {@code cl:indexed} folder aspect (issue #78). A genuinely empty folder exhausts the bounded
     * attempts and returns an empty list.
     */
    private List<Node> findDescendantFilesWithRetry(String folderId, DiscoveryTally tally) {
        int maxAttempts = Math.max(1, props.getDiscovery().getMaxAttempts());
        long intervalMs = Math.max(0, props.getDiscovery().getRetryIntervalMs());

        List<Node> nodes = List.of();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            nodes = searchService.findDescendantFiles(folderId, scopeResolver.getExcludedAspects());
            if (!nodes.isEmpty()) {
                if (attempt > 1) {
                    log.info("Discovery for folder {} returned {} descendants on attempt {}/{}",
                            folderId, nodes.size(), attempt, maxAttempts);
                }
                return nodes;
            }
            if (attempt < maxAttempts) {
                log.info("Discovery for folder {} found 0 descendants (attempt {}/{}); "
                                + "retrying in {}ms to allow Solr ANCESTOR/aspect commit",
                        folderId, attempt, maxAttempts, intervalMs);
                sleep(intervalMs);
            }
        }
        log.info("Discovery for folder {} found 0 descendants after {} attempt(s)", folderId, maxAttempts);
        tally.emptyAfterRetries(folderId, maxAttempts);
        return nodes;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean matchesExcludedPath(Node node) {
        String path = node.getPath() != null ? node.getPath().getName() : null;
        return scopeResolver.matchesExcludedPath(path);
    }

    private boolean matchesType(Node node, List<String> types) {
        return types == null || types.isEmpty() || types.contains(node.getNodeType());
    }
}
