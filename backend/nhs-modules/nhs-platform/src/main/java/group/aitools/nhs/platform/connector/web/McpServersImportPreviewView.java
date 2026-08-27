package group.aitools.nhs.platform.connector.web;

import java.util.List;

/**
 * 封装McpServers导入Preview相关的不可变数据。
 */
public record McpServersImportPreviewView(
    List<McpImportEntryView> entries
) {
}
