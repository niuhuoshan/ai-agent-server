package group.aitools.nhs.platform.connector.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装McpServers导入相关的不可变数据。
 */
public record McpServersImportRequest(
    @NotNull Map<String, Object> document,
    @NotBlank @Size(max = 128) String sourceKey,
    @NotBlank @Size(max = 128) String connectorKey,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Pattern(regexp = "global|personal") String scope,
    @Size(max = 132) String credentialRef,
    @NotBlank @Pattern(regexp = "active|disabled") String status
) {
}
