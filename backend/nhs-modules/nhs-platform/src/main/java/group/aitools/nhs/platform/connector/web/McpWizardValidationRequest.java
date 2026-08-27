package group.aitools.nhs.platform.connector.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装{@code McpWizardValidation}相关的不可变数据。
 * One step of the MCP registration wizard. */
public record McpWizardValidationRequest(
    @Min(1) @Max(3) int step,
    @NotBlank @Size(max = 128) String connectorKey,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 1024) String endpointUrl,
    @NotBlank @Size(max = 128) String namespace,
    @NotBlank @Size(max = 32) String transport,
    @NotBlank @Size(max = 16) String authType,
    @Size(max = 132) String credentialRef,
    @NotNull Map<String, Object> config
) {
}
