package org.hyland.contentlake.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Shared helper for LLM calls that must return a typed object rather than free text.
 *
 * <p>rag-service otherwise parses model output from free text (see {@link QueryExpansionService},
 * {@code LlmRerankService}). The three Sprint 4 features that need a structured answer -
 * intent-aware filter inference, running conversation summaries, and citation verification - route
 * their single LLM call through here so they share one convention:</p>
 * <ul>
 *   <li>A {@link BeanOutputConverter} appends JSON format instructions to the system prompt and
 *       parses the reply back into the target type.</li>
 *   <li>Any failure (no output, unparseable JSON, model error) degrades to the caller-supplied
 *       fallback rather than propagating, mirroring how the existing free-text callers degrade to a
 *       safe default. Every feature that uses this helper is opt-in, so a degraded call simply means
 *       "behave as if the feature were off for this request".</li>
 * </ul>
 *
 * <p>Injects {@link ChatModel} directly, not the {@code ragChatClient}: the chat client carries
 * {@link ContentLakeRetrievalAdvisor} as a default advisor, so calling it here would re-enter
 * retrieval from inside a post- or pre-retrieval step. This matches {@link QueryExpansionService}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredLlmCaller {

    private final ChatModel chatModel;

    /**
     * Runs one structured LLM call.
     *
     * @param system   the system instruction (the JSON format contract is appended automatically)
     * @param user     the user message
     * @param type     the class to parse the reply into
     * @param fallback returned verbatim on any failure
     * @param what     short label used in log messages
     * @return the parsed object, or {@code fallback} when the call or parse fails
     */
    public <T> T call(String system, String user, Class<T> type, T fallback, String what) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        String systemWithFormat = system + "\n\n" + converter.getFormat();
        List<Message> messages = List.of(new SystemMessage(systemWithFormat), new UserMessage(user));

        String text;
        try {
            ChatResponse response = chatModel.call(new Prompt(messages));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                log.warn("{} returned no output; using fallback", what);
                return fallback;
            }
            text = response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("{} call failed, using fallback: {}", what, e.getMessage());
            return fallback;
        }

        if (text == null || text.isBlank()) {
            log.warn("{} returned blank output; using fallback", what);
            return fallback;
        }

        try {
            T parsed = converter.convert(text.trim());
            return parsed != null ? parsed : fallback;
        } catch (Exception e) {
            log.warn("{} produced unparseable output, using fallback: {}", what, e.getMessage());
            return fallback;
        }
    }
}
