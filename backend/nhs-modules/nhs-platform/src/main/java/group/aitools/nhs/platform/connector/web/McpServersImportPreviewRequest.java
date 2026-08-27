package group.aitools.nhs.platform.connector.web;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 封装McpServers导入Preview相关的不可变数据。
 */
public record McpServersImportPreviewRequest(
    @NotNull Map<String, Object> document
) {
}
