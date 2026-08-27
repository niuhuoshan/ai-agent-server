package group.aitools.nhs.platform.connector.web;

import java.util.List;

/**
 * 封装Mcp导入Entry相关的不可变数据。
 */
public record McpImportEntryView(
    String sourceKey,
    String suggestedConnectorKey,
    String suggestedName,
    String endpointUrl,
    String transport,
    String authType,
    String authHeader,
    String credentialRef,
    boolean credentialRequired,
    boolean importable,
    List<String> diagnostics
) {
}
