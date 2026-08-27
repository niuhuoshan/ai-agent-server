package group.aitools.nhs.platform.connector.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装{@code McpConnectionTest}相关的不可变数据。
 * Credential-free result of a real MCP protocol handshake. */
public record McpConnectionTestView(
    boolean success,
    String protocolVersion,
    String serverName,
    int toolCount,
    List<McpConnectionToolPreviewView> tools,
    long latencyMs,
    LocalDateTime checkedAt
) {
}
