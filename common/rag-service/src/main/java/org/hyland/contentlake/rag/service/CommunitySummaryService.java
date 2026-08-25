package org.hyland.contentlake.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.client.HxprGraphService;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.hxpr.api.model.ACE;
import org.hyland.contentlake.model.ContentLakeIngestProperties;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.config.RagProperties.GraphProperties;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Community detection + summaries (#56, entity-centric). A community is a prominent {@code GlobalEntity}
 * together with the documents that mention it (the {@code mentioned_in} edges built during #54). For
 * each entity connected to at least {@code rag.graph.communities.min-size} documents, an LLM summary of
 * those documents is generated and stored as an hxpr {@code SysFile} carrying the
 * {@code graph_community} property. The summary document's ACL is the union of its member documents'
 * ACLs, so it is {@code sys_racl}-filtered like any other document (readable by anyone who can read at
 * least one member).
 *
 * <p>Detection is corpus-level and runs after extraction, so it is triggered explicitly
 * (see {@code POST /api/rag/graph/communities/rebuild}) rather than per document.</p>
 */
@Slf4j
public class CommunitySummaryService {

    private static final String TYPE_SYS_FILE = "SysFile";
    private static final String EXTRACTED_TEXT = ContentLakeIngestProperties.CONTENT_LAKE_EXTRACTED_TEXT;
    private static final String GRAPH_COMMUNITY = ContentLakeIngestProperties.GRAPH_COMMUNITY;

    private final HxprGraphService graphService;
    private final HxprService hxprService;
    private final HxprDocumentApi documentApi;
    private final ChatModel chatModel;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CommunitySummaryService(HxprGraphService graphService, HxprService hxprService,
                                   HxprDocumentApi documentApi, ChatModel chatModel, RagProperties ragProperties) {
        this.graphService = graphService;
        this.hxprService = hxprService;
        this.documentApi = documentApi;
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
    }

    /**
     * (Re)builds community summaries from the current graph. Idempotent per community (keyed by the
     * community document's name). Returns the number of communities written.
     */
    public int rebuild() {
        GraphProperties cfg = ragProperties.getGraph();
        String graphDbId = graphService.resolveGraphDbId(cfg.getGraphdbId(), cfg.getGraphdbName());
        if (graphDbId == null) {
            log.warn("Community rebuild skipped: could not resolve graphDB id (name '{}').", cfg.getGraphdbName());
            return 0;
        }

        List<Community> communities = detectCommunities(graphDbId, cfg);
        if (communities.isEmpty()) {
            log.info("Community rebuild: no entity connects >= {} documents.", cfg.getCommunities().getMinSize());
            return 0;
        }

        hxprService.ensureFolder(cfg.getCommunities().getBasePath());
        int written = 0;
        for (Community community : communities) {
            try {
                if (writeCommunity(community, cfg)) {
                    written++;
                }
            } catch (RuntimeException e) {
                log.error("Failed to write community '{}'; continuing.", community.name, e);
            }
        }
        log.info("Community rebuild complete: {} communities written.", written);
        return written;
    }

    /** Queries the graph for entities and their member documents, keeping those above the size threshold. */
    private List<Community> detectCommunities(String graphDbId, GraphProperties cfg) {
        List<Community> communities = new ArrayList<>();
        try {
            // first is capped by hxpr (graphdb.query.graphql.pagination.max-first, default 1000).
            String query = "query { queryGlobalEntity(first: 1000) { canonical_name mentioned_in { documentId } } }";
            String json = graphService.query(graphDbId, query, null);
            if (json == null || json.isBlank()) {
                return communities;
            }
            for (JsonNode entity : objectMapper.readTree(json).path("queryGlobalEntity")) {
                String name = entity.path("canonical_name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                Set<String> docIds = new LinkedHashSet<>();
                for (JsonNode m : entity.path("mentioned_in")) {
                    String id = m.path("documentId").asText(null);
                    if (id != null) {
                        docIds.add(id);
                    }
                }
                if (docIds.size() >= cfg.getCommunities().getMinSize()) {
                    communities.add(new Community(name, new ArrayList<>(docIds)));
                }
            }
        } catch (Exception e) {
            log.error("Community detection query failed.", e);
            return List.of();
        }
        communities.sort((a, b) -> Integer.compare(b.docIds.size(), a.docIds.size()));
        int cap = cfg.getCommunities().getMaxCommunities();
        return communities.size() > cap ? communities.subList(0, cap) : communities;
    }

    private boolean writeCommunity(Community community, GraphProperties cfg) {
        List<HxprDocument> members = fetchMembers(community.docIds);
        if (members.isEmpty()) {
            return false;
        }
        String summary = summarize(community.name, members, cfg.getCommunities().getMemberSnippetChars());
        if (summary == null || summary.isBlank()) {
            return false;
        }

        // Union the members' ACLs so the summary is readable by anyone who can read any member.
        Set<ACE> acl = new LinkedHashSet<>();
        Set<String> cinRead = new LinkedHashSet<>();
        for (HxprDocument m : members) {
            if (m.getSysAcl() != null) {
                acl.addAll(m.getSysAcl());
            }
            if (m.getCinRead() != null) {
                cinRead.addAll(m.getCinRead());
            }
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put(GRAPH_COMMUNITY, community.name);
        props.put(EXTRACTED_TEXT, summary);

        String name = safeName(community.name);
        String path = cfg.getCommunities().getBasePath() + "/" + name;
        HxprDocument existing = hxprService.findByPath(path);

        HxprDocument doc = new HxprDocument();
        doc.setSysPrimaryType(TYPE_SYS_FILE);
        doc.setSysMixinTypes(List.of("CinRemote"));  // required for the cin_* properties to be valid
        doc.setSysName(name);
        doc.setCinIngestProperties(props);
        doc.setCinIngestPropertyNames(new ArrayList<>(props.keySet()));
        doc.setSysAcl(new ArrayList<>(acl));
        doc.setCinRead(new ArrayList<>(cinRead));

        if (existing != null) {
            documentApi.updateById(existing.getSysId(), doc);
        } else {
            hxprService.createDocument(cfg.getCommunities().getBasePath(), doc);
        }
        return true;
    }

    private List<HxprDocument> fetchMembers(List<String> docIds) {
        StringBuilder clause = new StringBuilder();
        for (int i = 0; i < docIds.size(); i++) {
            if (i > 0) {
                clause.append(" OR ");
            }
            clause.append("sys_id = '").append(docIds.get(i).replaceAll("[^A-Za-z0-9_\\-]", "")).append('\'');
        }
        HxprDocument.QueryResult result =
                hxprService.query("SELECT * FROM SysContent WHERE (" + clause + ")", docIds.size(), 0);
        return result != null && result.getDocuments() != null ? result.getDocuments() : List.of();
    }

    private String summarize(String entity, List<HxprDocument> members, int memberSnippetChars) {
        StringBuilder body = new StringBuilder();
        for (HxprDocument m : members) {
            String text = extractedText(m, memberSnippetChars);
            if (!text.isBlank()) {
                body.append("--- ").append(m.getSysName() != null ? m.getSysName() : "document").append(" ---\n")
                        .append(text).append("\n\n");
            }
        }
        if (body.length() == 0) {
            return null;
        }
        String system = "You write a concise summary of everything the provided documents say about a "
                + "specific subject. Focus only on that subject; be factual and specific; 3-6 sentences.";
        String user = "Subject: " + entity + "\n\nDocuments:\n" + body;
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                return response.getResult().getOutput().getText();
            }
        } catch (Exception e) {
            log.warn("Community summary LLM call failed for '{}': {}", entity, e.getMessage());
        }
        return null;
    }

    private static String extractedText(HxprDocument doc, int maxChars) {
        if (doc.getCinIngestProperties() == null) {
            return "";
        }
        Object t = doc.getCinIngestProperties().get(EXTRACTED_TEXT);
        if (t == null) {
            return "";
        }
        String s = t.toString();
        return s.length() > maxChars ? s.substring(0, maxChars) : s;
    }

    private static String safeName(String entity) {
        String slug = entity.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            slug = "community";
        }
        return "community-" + slug;
    }

    private record Community(String name, List<String> docIds) {
    }
}
