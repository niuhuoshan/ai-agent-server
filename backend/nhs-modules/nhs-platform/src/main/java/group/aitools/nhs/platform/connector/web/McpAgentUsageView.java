package group.aitools.nhs.platform.connector.web;

/**
 * 封装Mcp智能体Usage相关的不可变数据。
 * Secret-free usage summary for one Agent bound to an MCP connector. */
public record McpAgentUsageView(
    Long id,
    String name,
    String displayName,
    boolean isEnabled,
    boolean active,
    int versionCount
) {
}
