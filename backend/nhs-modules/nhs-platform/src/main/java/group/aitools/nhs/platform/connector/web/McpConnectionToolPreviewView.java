package group.aitools.nhs.platform.connector.web;

/**
 * 封装McpConnection工具Preview相关的不可变数据。
 * Credential-free tool identity returned by an MCP connection preview. */
public record McpConnectionToolPreviewView(
    String externalName,
    String name,
    String description
) {
}
