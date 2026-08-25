package org.hyland.contentlake.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprGraphService;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.config.HxprProperties;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.config.RagProperties.GraphProperties;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SourceDocument;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Graph-augmented retrieval (#55). Given the reranked seed hits, traverses the hxpr knowledge graph
 * from the seed documents through their entities to related documents, then re-applies the same
 * {@code sys_racl} permission filter the vector path uses (the graph query runs under the service
 * account, so its {@code @auth} filtering cannot be trusted per end-user), and returns the permitted
 * related documents as additional {@link SearchHit}s plus the entity names discovered.
 */
@Slf4j
public class GraphAugmentationService {

    private final HxprGraphService graphService;
    private final HybridSearchService hybridSearchService;
    private final HxprService hxprService;
    private final SourceMetadataResolver sourceMetadataResolver;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GraphAugmentationService(HxprGraphService graphService,
                                    HybridSearchService hybridSearchService,
                                    HxprService hxprService,
                                    SourceMetadataResolver sourceMetadataResolver,
                                    RagProperties ragProperties) {
        this.graphService = graphService;
        this.hybridSearchService = hybridSearchService;
        this.hxprService = hxprService;
        this.sourceMetadataResolver = sourceMetadataResolver;
        this.ragProperties = ragProperties;
    }

    /** Result of a graph expansion: extra document hits and the entity names traversed. */
    public record Expansion(List<SearchHit> graphHits, List<String> entities) {
        public static Expansion empty() {
            return new Expansion(List.of(), List.of());
        }
    }

    /**
     * Expands the seed hits via the graph. Best-effort: returns an empty expansion on any failure.
     *
     * @param seedHits          the reranked vector/hybrid hits to seed traversal from
     * @param sourceType        optional source-type scope for the permission filter (may be null)
     * @param includeCommunities when true, also include community summaries (#56) for the traversed entities
     */
    public Expansion expand(List<SearchHit> seedHits, String sourceType, boolean includeCommunities) {
        GraphProperties cfg = ragProperties.getGraph();
        try {
            String graphDbId = graphService.resolveGraphDbId(cfg.getGraphdbId(), cfg.getGraphdbName());
            if (graphDbId == null) {
                log.warn("Graph expansion skipped: could not resolve graphDB id (name '{}').", cfg.getGraphdbName());
                return Expansion.empty();
            }

            List<String> seedIds = seedDocumentIds(seedHits, cfg.getSeedDocuments());
            if (seedIds.isEmpty()) {
                return Expansion.empty();
            }

            String json = graphService.query(graphDbId, buildTraversalQuery(seedIds), null);
            if (json == null || json.isBlank()) {
                return Expansion.empty();
            }

            Set<String> seedSet = new LinkedHashSet<>(seedIds);
            Set<String> relatedIds = new LinkedHashSet<>();
            Set<String> entities = new LinkedHashSet<>();
            parseTraversal(json, seedSet, relatedIds, entities, cfg.getMaxExpandedDocuments());

            List<SearchHit> graphHits = relatedIds.isEmpty()
                    ? new ArrayList<>()
                    : fetchPermittedDocuments(relatedIds, seedSet, sourceType, cfg);

            // #56: community summaries for the traversed entities (permission-filtered, deduped).
            if (includeCommunities && !entities.isEmpty()) {
                graphHits.addAll(fetchCommunitySummaries(entities, sourceType, seedSet, graphHits, cfg));
            }

            log.info("Graph expansion: {} entities, {} related docs, {} hits ({} communities) after ACL filter",
                    entities.size(), relatedIds.size(), graphHits.size(), includeCommunities ? "with" : "no");
            return new Expansion(graphHits, new ArrayList<>(entities));
        } catch (RuntimeException e) {
            log.error("Graph expansion failed; continuing with vector results only.", e);
            return Expansion.empty();
        }
    }

    /**
     * Fetches community-summary documents (#56) whose {@code graph_community} matches one of the
     * traversed entities, permission-filtered and deduped against seeds and existing graph hits.
     */
    private List<SearchHit> fetchCommunitySummaries(Set<String> entities, String sourceType,
                                                    Set<String> seedSet, List<SearchHit> existing,
                                                    GraphProperties cfg) {
        try {
            String basePath = cfg.getCommunities().getBasePath();
            String hxql = hybridSearchService.buildCurrentUserPermissionFilter(sourceType,
                    "sys_parentPath = '" + basePath.replace("'", "''") + "'");
            HxprDocument.QueryResult result = hxprService.query(hxql, cfg.getMaxExpandedDocuments(), 0);
            if (result == null || result.getDocuments() == null) {
                return List.of();
            }
            Set<String> wanted = new LinkedHashSet<>();
            for (String e : entities) {
                wanted.add(e.toLowerCase(java.util.Locale.ROOT));
            }
            Set<String> seen = new LinkedHashSet<>(seedSet);
            for (SearchHit h : existing) {
                if (h.getSourceDocument() != null && h.getSourceDocument().getDocumentId() != null) {
                    seen.add(h.getSourceDocument().getDocumentId());
                }
            }

            List<SearchHit> hits = new ArrayList<>();
            for (HxprDocument doc : result.getDocuments()) {
                if (doc.getCinIngestProperties() == null) {
                    continue;
                }
                Object community = doc.getCinIngestProperties().get("graph_community");
                if (community == null || !wanted.contains(community.toString().toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                String id = doc.getSysId();
                if (id == null || !seen.add(id)) {
                    continue;
                }
                SourceDocument sd = sourceMetadataResolver.resolveSourceDocument(id, doc);
                if (sd != null && (sd.getName() == null || sd.getName().isBlank())) {
                    sd.setName("Community summary: " + community);  // community docs have no source-name metadata
                }
                hits.add(SearchHit.builder()
                        .rank(0)
                        .score(0.0)
                        .chunkText(snippet(doc, cfg.getSnippetChars()))
                        .sourceDocument(sd)
                        .build());
            }
            return hits;
        } catch (RuntimeException e) {
            log.warn("Community summary lookup failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<String> seedDocumentIds(List<SearchHit> hits, int limit) {
        Set<String> ids = new LinkedHashSet<>();
        for (SearchHit hit : hits) {
            if (hit.getSourceDocument() != null && hit.getSourceDocument().getDocumentId() != null) {
                ids.add(hit.getSourceDocument().getDocumentId());
            }
            if (ids.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Single-hop Dgraph GraphQL: seed documents -> their entities -> other documents mentioning them.
     * Ids are inlined (hxpr query vars are string-only, so a list variable cannot be passed).
     */
    private static String buildTraversalQuery(List<String> seedIds) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < seedIds.size(); i++) {
            if (i > 0) {
                in.append(',');
            }
            in.append('"').append(sanitizeId(seedIds.get(i))).append('"');
        }
        return "query { queryDocument(filter: {documentId: {in: [" + in + "]}}) "
                + "{ documentId has_global_entity { canonical_name mentioned_in { documentId } } } }";
    }

    private void parseTraversal(String json, Set<String> seedSet, Set<String> relatedIds,
                                Set<String> entities, int maxDocs) throws RuntimeException {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode docs = root.path("queryDocument");
            for (JsonNode doc : docs) {
                for (JsonNode entity : doc.path("has_global_entity")) {
                    String name = entity.path("canonical_name").asText(null);
                    if (name != null && !name.isBlank()) {
                        entities.add(name);
                    }
                    for (JsonNode mentioned : entity.path("mentioned_in")) {
                        String id = mentioned.path("documentId").asText(null);
                        if (id != null && !seedSet.contains(id) && relatedIds.size() < maxDocs) {
                            relatedIds.add(id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse graph traversal result", e);
        }
    }

    private List<SearchHit> fetchPermittedDocuments(Set<String> relatedIds, Set<String> seedSet,
                                                    String sourceType, GraphProperties cfg) {
        String idClause = buildSysIdClause(relatedIds);
        String hxql = hybridSearchService.buildCurrentUserPermissionFilter(sourceType, idClause);
        HxprDocument.QueryResult result = hxprService.query(hxql, cfg.getMaxExpandedDocuments(), 0);
        if (result == null || result.getDocuments() == null) {
            return List.of();
        }

        List<SearchHit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(seedSet);
        for (HxprDocument doc : result.getDocuments()) {
            String id = doc.getSysId();
            if (id == null || !seen.add(id)) {
                continue;
            }
            SourceDocument sd = sourceMetadataResolver.resolveSourceDocument(id, doc);
            hits.add(SearchHit.builder()
                    .rank(0)
                    .score(0.0)
                    .chunkText(snippet(doc, cfg.getSnippetChars()))
                    .sourceDocument(sd)
                    .build());
        }
        return hits;
    }

    private static String snippet(HxprDocument doc, int maxChars) {
        if (doc.getCinIngestProperties() == null) {
            return "";
        }
        Object text = doc.getCinIngestProperties().get("contentLake_extractedText");
        if (text == null) {
            return "";
        }
        String s = text.toString();
        return s.length() > maxChars ? s.substring(0, maxChars) : s;
    }

    private static String buildSysIdClause(Set<String> ids) {
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (String id : ids) {
            if (!first) {
                sb.append(" OR ");
            }
            sb.append("sys_id = '").append(sanitizeId(id)).append('\'');
            first = false;
        }
        return sb.append(')').toString();
    }

    /** Document ids here are hxpr sys_ids (UUIDs); strip anything outside the UUID charset defensively. */
    private static String sanitizeId(String id) {
        return id.replaceAll("[^A-Za-z0-9_\\-]", "");
    }
}
