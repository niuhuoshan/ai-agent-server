package group.aitools.nhs.platform.connector.web;

import java.util.List;

/**
 * 封装Mcp运行时Overview相关的不可变数据。
 * Combined health, mount lifecycle and recent usage projection for one connector. */
public record McpRuntimeOverviewView(
    McpRuntimeHealthView health,
    List<McpRuntimeMountView> mounts,
    List<McpUsageDetailView> usage
) {
}
