package group.aitools.nhs.platform.connector.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装{@code McpConnectionTest}相关的不可变数据。
 * Unsaved MCP connector settings used for a credential-safe protocol handshake. */
public record McpConnectionTestRequest(
    @Positive Long connectorId,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 1024) String endpointUrl,
    @Size(max = 132) String credentialRef,
    @NotNull Map<String, Object> config
) {
}
