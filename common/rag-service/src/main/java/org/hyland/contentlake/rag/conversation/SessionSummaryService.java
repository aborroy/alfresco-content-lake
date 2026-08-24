package org.hyland.contentlake.rag.conversation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.rag.config.RagProperties;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Long-term conversation memory: an LLM-maintained running summary that preserves key facts, named
 * entities, chosen filters, and user intent beyond the sliding turn window and across restarts.
 *
 * <p>{@link ConversationMemoryService} keeps only the last N turns in memory (lost on restart or
 * once they age out). This service persists a compact summary as an hxpr document under the
 * configured sessions folder, giving durability and ACL protection at no extra infrastructure cost.
 * It is off by default ({@code rag.conversation.summary.enabled}) until that folder is provisioned.</p>
 *
 * <p>Uses {@link ChatModel} directly (the summary is prose, not structured) and treats every failure
 * as non-fatal: a failed update or load simply leaves the conversation running on its in-memory
 * window, exactly as it did before this feature existed.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSummaryService {

    /** cin_ingestProperties key holding the running summary text. */
    static final String SUMMARY_PROPERTY = "contentLake_sessionSummary";

    private static final String SYSTEM_PROMPT = """
            You maintain a concise running summary of a conversation between a user and a document
            assistant. Given the previous summary (if any) and the latest turns, produce an updated
            summary that preserves: key facts established, named entities, any filters or scope the
            user chose, and the user's overall intent. Drop small talk. Keep it under 200 words and
            write it as plain notes, not a dialogue. Return ONLY the updated summary.""";

    private final ChatModel chatModel;
    private final HxprService hxprService;
    private final HxprDocumentApi documentApi;
    private final RagProperties ragProperties;

    public boolean isEnabled() {
        return ragProperties.getConversation().getSummary().isEnabled();
    }

    /**
     * Loads the persisted running summary for a session, or {@code null} if none exists, the feature
     * is disabled, or the read fails.
     */
    public String loadSummary(String sessionId) {
        if (!isEnabled() || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            HxprDocument doc = hxprService.findByPath(summaryPath(sessionId));
            if (doc == null || doc.getCinIngestProperties() == null) {
                return null;
            }
            Object summary = doc.getCinIngestProperties().get(SUMMARY_PROPERTY);
            return summary != null ? summary.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to load session summary for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Regenerates and persists the running summary after a completed turn. Best-effort: any failure
     * is logged and swallowed.
     */
    public void updateAfterTurn(String sessionId, List<ConversationTurn> turns) {
        if (!isEnabled() || sessionId == null || sessionId.isBlank() || turns == null || turns.isEmpty()) {
            return;
        }
        try {
            String previous = loadSummary(sessionId);
            String updated = generateSummary(previous, turns);
            if (updated == null || updated.isBlank()) {
                return;
            }
            persist(sessionId, updated.trim());
        } catch (Exception e) {
            log.warn("Failed to update session summary for {}: {}", sessionId, e.getMessage());
        }
    }

    private String generateSummary(String previous, List<ConversationTurn> turns) {
        StringBuilder user = new StringBuilder();
        if (previous != null && !previous.isBlank()) {
            user.append("Previous summary:\n").append(previous.trim()).append("\n\n");
        }
        user.append("Latest turns:\n");
        for (ConversationTurn turn : turns) {
            if (turn.getContent() == null || turn.getContent().isBlank()) {
                continue;
            }
            String role = turn.getRole() == ConversationTurn.Role.ASSISTANT ? "Assistant" : "User";
            user.append(role).append(": ").append(turn.getContent().trim()).append("\n");
        }
        user.append("\nUpdated summary:\n");

        List<Message> messages = List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(user.toString()));
        ChatResponse response = chatModel.call(new Prompt(messages));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private void persist(String sessionId, String summary) {
        String basePath = basePath();
        hxprService.ensureFolder(basePath);

        String path = summaryPath(sessionId);
        HxprDocument existing = hxprService.findByPath(path);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put(SUMMARY_PROPERTY, summary);

        HxprDocument doc = new HxprDocument();
        doc.setSysPrimaryType("SysFile");
        doc.setSysName(safeName(sessionId));
        doc.setCinIngestProperties(props);
        // cin_ingestPropertyNames must always mirror cin_ingestProperties.keySet().
        doc.setCinIngestPropertyNames(new ArrayList<>(props.keySet()));

        if (existing != null) {
            documentApi.updateById(existing.getSysId(), doc);
        } else {
            hxprService.createDocument(basePath, doc);
        }
    }

    private String basePath() {
        String base = ragProperties.getConversation().getSummary().getBasePath();
        if (base == null || base.isBlank()) {
            base = "/_sessions";
        }
        return base.endsWith("/") && base.length() > 1 ? base.substring(0, base.length() - 1) : base;
    }

    private String summaryPath(String sessionId) {
        String base = basePath();
        return "/".equals(base) ? "/" + safeName(sessionId) : base + "/" + safeName(sessionId);
    }

    /** Session ids carry ':' (e.g. "user:alice"); reduce to a filesystem/path-safe document name. */
    private String safeName(String sessionId) {
        return sessionId.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
