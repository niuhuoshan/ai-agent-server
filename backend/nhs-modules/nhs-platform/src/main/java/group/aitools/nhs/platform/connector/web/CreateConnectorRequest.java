package group.aitools.nhs.platform.connector.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装Create连接器相关的不可变数据。
 */
public record CreateConnectorRequest(
    @NotBlank @Size(max = 128) String connectorKey,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Pattern(regexp = "api|mcp|search") String providerType,
    @NotBlank @Pattern(regexp = "global|personal") String scope,
    @NotBlank @Size(max = 1024) String endpointUrl,
    @Size(max = 132) String credentialRef,
    @NotNull Map<String, Object> config,
    @NotBlank @Pattern(regexp = "active|disabled") String status
) {
}
