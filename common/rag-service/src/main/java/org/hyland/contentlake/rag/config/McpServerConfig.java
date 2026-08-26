package org.hyland.contentlake.rag.config;

import org.hyland.contentlake.rag.mcp.ContentLakeMcpServer;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link ContentLakeMcpServer} tools with the Spring AI MCP server (#61).
 *
 * <p>The MCP server auto-configuration (from {@code spring-ai-starter-mcp-server-webmvc}) discovers
 * {@link ToolCallbackProvider} beans and publishes their {@code @Tool} methods over the MCP transport.
 * Gated by {@code rag.mcp.enabled} so the whole MCP surface can be switched off.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "rag.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider contentLakeMcpTools(ContentLakeMcpServer contentLakeMcpServer) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(contentLakeMcpServer)
                .build();
    }
}
