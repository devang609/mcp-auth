package com.healthcare.rag.config;

import com.healthcare.rag.mcp.HealthcareTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code @Tool}-annotated methods on {@link HealthcareTools} with the MCP
 * server. Spring AI's MCP server starter provides only the transport (Streamable HTTP over
 * WebMVC) and JSON-RPC plumbing; all tool logic and access control are our own.
 */
@Configuration
public class McpConfig {

    @Bean
    ToolCallbackProvider healthcareToolCallbacks(HealthcareTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
