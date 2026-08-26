package org.hyland.contentlake.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the full application context loads with the MCP server enabled.
 *
 * <p>Guards against bean-wiring regressions that unit tests miss - in particular the startup circular
 * dependency where exposing the MCP tools as a {@code ToolCallbackProvider} (collected by Spring AI's
 * tool-calling autoconfiguration to build the {@code ChatModel}) pulled in {@code SemanticSearchService}
 * -> query expansion -> the same {@code ChatModel}. Bean creation does not call any external service,
 * so the context loads offline with dummy endpoints.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.openai.base-url=http://localhost:0/v1",
                "spring.ai.openai.api-key=test",
                "spring.ai.openai.chat.options.model=test-chat",
                "spring.ai.openai.embedding.model=test-embed",
                "rag.mcp.enabled=true",
                "rag.agentic-tools.enabled=true",
                "rag.graph.enabled=false"
        })
class ApplicationContextLoadsTest {

    @Test
    void contextLoads() {
        // Success = the ApplicationContext refreshed without an unresolvable circular reference.
    }
}
