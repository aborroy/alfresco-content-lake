package org.hyland.contentlake.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprGraphService;
import org.hyland.contentlake.config.HxprProperties;
import org.hyland.contentlake.config.HxprProperties.GraphConfig;
import org.hyland.contentlake.model.graph.GraphEntityUpsert;
import org.hyland.contentlake.model.graph.GraphRelationshipUpsert;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GraphRAG population at ingestion time (#54): extracts named entities from a document's text with the
 * LLM and links them into the hxpr knowledge graph as {@code GlobalEntity} nodes connected to the
 * document via {@code Document -has_global_entity-> GlobalEntity}.
 *
 * <p>The graph {@code Document} node and its {@code acl} are owned by hxpr (synced from the ingested
 * document); this service only upserts the child entities and the mention edges. It is best-effort:
 * any failure is logged and swallowed so graph problems never break the content pipeline.</p>
 */
@Slf4j
public class GraphIngestionService {

    /** Entity kinds the extractor is allowed to emit (stored as GlobalEntity.entity_type). */
    private static final Set<String> ALLOWED_TYPES = Set.of("Person", "Organization", "Location", "Concept");
    private static final String DOC_CLIENT_REF = "doc";
    private static final String TYPE_DOCUMENT = "Document";
    private static final String TYPE_GLOBAL_ENTITY = "GlobalEntity";
    private static final String EDGE_HAS_GLOBAL_ENTITY = "has_global_entity";

    private static final String SYSTEM_PROMPT = """
            You extract named entities from a document for a knowledge graph.
            Return only entities that are clearly named in the text. For each, give:
            - name: the canonical name (deduplicate variants to one canonical form)
            - type: exactly one of Person, Organization, Location, Concept
            - aliases: other surface forms mentioned (may be empty)
            Do not invent entities. Prefer precision over recall. Omit generic terms.""";

    private final ChatModel chatModel;
    private final HxprGraphService graphService;
    private final HxprProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Serializes the graph-write section (resolve -> upsert -> link) within this ingester so concurrent
     * transform workers cannot both create a GlobalEntity for the same canonical name. The LLM
     * extraction itself runs outside the lock. Combined with {@link #entityUidCache}, this makes
     * cross-document entity sharing reliable within a single ingester process. (v2 GlobalEntity has no
     * {@code @id}, so there is no atomic upsert-by-name to rely on instead.)
     */
    private final java.util.concurrent.locks.ReentrantLock writeLock = new java.util.concurrent.locks.ReentrantLock();

    /** In-process {@code lowercased-canonical-name -> uid} cache; avoids Dgraph read-after-write lag. */
    private final Map<String, String> entityUidCache = new java.util.concurrent.ConcurrentHashMap<>();

    public GraphIngestionService(ChatModel chatModel, HxprGraphService graphService, HxprProperties properties) {
        this.chatModel = chatModel;
        this.graphService = graphService;
        this.properties = properties;
    }

    /**
     * Extracts entities from {@code text} and links them to the document in the graph.
     *
     * @return the canonical names of the linked entities (for storing as a back-reference), or an
     *         empty list if extraction/linking did not happen
     */
    public List<String> ingest(String hxprDocId, String text, String documentName) {
        GraphConfig cfg = properties.getGraph();
        if (!cfg.isExtractionEnabled()) {
            return List.of();
        }
        try {
            String graphDbId = graphService.resolveGraphDbId(cfg.getGraphdbId(), cfg.getGraphdbName());
            if (graphDbId == null) {
                log.warn("Graph extraction skipped: could not resolve graphDB id (name '{}').", cfg.getGraphdbName());
                return List.of();
            }

            List<ExtractedEntity> entities = dedupeAndCap(extractEntities(text, documentName), cfg.getMaxEntitiesPerDocument());
            if (entities.isEmpty()) {
                log.debug("No entities extracted for doc {}", hxprDocId);
                return List.of();
            }

            // The graph-write section is serialized so concurrent workers do not create duplicate
            // GlobalEntity nodes for the same name (see writeLock/entityUidCache).
            writeLock.lock();
            try {
                return linkEntities(graphDbId, hxprDocId, entities);
            } finally {
                writeLock.unlock();
            }
        } catch (RuntimeException e) {
            log.error("Graph extraction failed for doc {}; continuing.", hxprDocId, e);
            return List.of();
        }
    }

    /**
     * Resolves each extracted entity to an existing GlobalEntity uid (cache first, then a graph lookup
     * for cache misses), creates GlobalEntity nodes only for genuinely new names, and links the
     * document to every entity. Must be called under {@link #writeLock}.
     */
    private List<String> linkEntities(String graphDbId, String hxprDocId, List<ExtractedEntity> entities) {
        // 1. Resolve existing entities: check the in-process cache, then the graph for cache misses.
        Map<String, String> resolved = new HashMap<>();
        List<ExtractedEntity> cacheMisses = new ArrayList<>();
        for (ExtractedEntity e : entities) {
            String uid = entityUidCache.get(nameKey(e.getName()));
            if (uid != null) {
                resolved.put(nameKey(e.getName()), uid);
            } else {
                cacheMisses.add(e);
            }
        }
        if (!cacheMisses.isEmpty()) {
            resolveExistingEntityUids(graphDbId, cacheMisses).forEach((key, uid) -> {
                entityUidCache.put(key, uid);
                resolved.put(key, uid);
            });
        }

        // 2. Upsert the Document (to obtain its uid) + a GlobalEntity only for names not already resolved.
        List<GraphEntityUpsert> upserts = new ArrayList<>();
        upserts.add(new GraphEntityUpsert(null, DOC_CLIENT_REF, TYPE_DOCUMENT,
                new LinkedHashMap<>(Map.of("documentId", hxprDocId))));
        Map<Integer, String> newClientRefs = new HashMap<>();
        for (int i = 0; i < entities.size(); i++) {
            ExtractedEntity e = entities.get(i);
            if (resolved.containsKey(nameKey(e.getName()))) {
                continue;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("documentId", hxprDocId);
            props.put("canonical_name", e.getName());
            props.put("entity_type", e.getType());
            if (e.getAliases() != null && !e.getAliases().isEmpty()) {
                props.put("aliases", e.getAliases());
            }
            String clientRef = "e" + i;
            upserts.add(new GraphEntityUpsert(null, clientRef, TYPE_GLOBAL_ENTITY, props));
            newClientRefs.put(i, clientRef);
        }

        Map<String, String> uids = graphService.upsertEntities(graphDbId, upserts);
        String docUid = uids.get(DOC_CLIENT_REF);
        if (docUid == null) {
            log.warn("Graph extraction: no uid returned for Document {}; entities upserted without mention edges.",
                    hxprDocId);
            return canonicalNames(entities);
        }

        // 3. Cache the newly created entity uids so later documents reuse them.
        for (Map.Entry<Integer, String> entry : newClientRefs.entrySet()) {
            String uid = uids.get(entry.getValue());
            if (uid != null) {
                String key = nameKey(entities.get(entry.getKey()).getName());
                entityUidCache.put(key, uid);
                resolved.put(key, uid);
            }
        }

        // 4. Link the document to each entity (existing or newly created).
        List<GraphRelationshipUpsert> rels = new ArrayList<>();
        for (ExtractedEntity e : entities) {
            String entityUid = resolved.get(nameKey(e.getName()));
            if (entityUid != null) {
                rels.add(new GraphRelationshipUpsert(docUid, entityUid, EDGE_HAS_GLOBAL_ENTITY,
                        TYPE_DOCUMENT, TYPE_GLOBAL_ENTITY));
            }
        }
        graphService.upsertRelationships(graphDbId, rels);

        log.info("Graph extraction: linked {} entities to doc {} ({} new)",
                rels.size(), hxprDocId, newClientRefs.size());
        return canonicalNames(entities);
    }

    private List<ExtractedEntity> extractEntities(String text, String documentName) {
        int max = properties.getGraph().getMaxExtractionChars();
        String body = text.length() > max ? text.substring(0, max) : text;
        String user = "Document: " + (documentName != null ? documentName : "") + "\n\n" + body;

        BeanOutputConverter<ExtractedEntities> converter = new BeanOutputConverter<>(ExtractedEntities.class);
        String system = SYSTEM_PROMPT + "\n\n" + converter.getFormat();
        List<Message> messages = List.of(new SystemMessage(system), new UserMessage(user));

        ChatResponse response = chatModel.call(new Prompt(messages));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return List.of();
        }
        String out = response.getResult().getOutput().getText();
        if (out == null || out.isBlank()) {
            return List.of();
        }
        ExtractedEntities parsed = converter.convert(out.trim());
        return parsed != null && parsed.getEntities() != null ? parsed.getEntities() : List.of();
    }

    /**
     * Looks up GlobalEntity nodes already in the graph by canonical name, returning a
     * {@code lowercased-name -> uid} map. Enables cross-document entity sharing. Best-effort: returns
     * an empty map on any failure (so extraction falls back to creating new nodes).
     */
    private Map<String, String> resolveExistingEntityUids(String graphDbId, List<ExtractedEntity> entities) {
        Map<String, String> byName = new HashMap<>();
        try {
            StringBuilder in = new StringBuilder();
            boolean first = true;
            for (ExtractedEntity e : entities) {
                if (!first) {
                    in.append(',');
                }
                in.append(objectMapper.writeValueAsString(e.getName()));  // JSON-escaped, quoted
                first = false;
            }
            String query = "query { queryGlobalEntity(filter: {canonical_name: {in: [" + in + "]}}) "
                    + "{ uid canonical_name } }";
            String json = graphService.query(graphDbId, query, null);
            if (json == null || json.isBlank()) {
                return byName;
            }
            JsonNode arr = objectMapper.readTree(json).path("queryGlobalEntity");
            for (JsonNode node : arr) {
                String name = node.path("canonical_name").asText(null);
                String uid = node.path("uid").asText(null);
                if (name != null && uid != null) {
                    byName.putIfAbsent(nameKey(name), uid);
                }
            }
        } catch (Exception e) {
            log.debug("Entity resolution lookup failed ({}); will create new entities.", e.getMessage());
        }
        return byName;
    }

    private static String nameKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static List<ExtractedEntity> dedupeAndCap(List<ExtractedEntity> in, int cap) {
        Map<String, ExtractedEntity> byName = new LinkedHashMap<>();
        for (ExtractedEntity e : in) {
            if (e == null || e.getName() == null || e.getName().isBlank() || e.getType() == null) {
                continue;
            }
            if (!ALLOWED_TYPES.contains(e.getType())) {
                continue;
            }
            String key = e.getName().trim().toLowerCase(Locale.ROOT);
            byName.putIfAbsent(key, e);
            if (byName.size() >= cap) {
                break;
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static List<String> canonicalNames(List<ExtractedEntity> entities) {
        List<String> names = new ArrayList<>(entities.size());
        for (ExtractedEntity e : entities) {
            names.add(e.getName());
        }
        return names;
    }

    /** Structured-output bean: the list of entities the LLM extracted. */
    @Data
    @NoArgsConstructor
    public static class ExtractedEntities {
        private List<ExtractedEntity> entities = new ArrayList<>();
    }

    /** One extracted entity. */
    @Data
    @NoArgsConstructor
    public static class ExtractedEntity {
        private String name;
        private String type;
        private List<String> aliases = new ArrayList<>();
    }
}
