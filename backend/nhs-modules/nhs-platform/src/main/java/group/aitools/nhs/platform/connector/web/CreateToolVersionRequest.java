package group.aitools.nhs.platform.connector.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装Create工具版本相关的不可变数据。
 */
public record CreateToolVersionRequest(
    @NotBlank @Size(max = 128) String name,
    @Size(max = 12000) String description,
    @Positive Long connectorId,
    @NotBlank @Pattern(regexp = "builtin|api|search|sql|sandbox") String toolType,
    @NotBlank @Pattern(regexp = "R0|R1|R2|R3") String riskLevel,
    @NotNull Map<String, Object> parameterSchema,
    @NotNull Map<String, Object> executionPolicy,
    @Size(max = 255) String externalName,
    @NotBlank @Pattern(regexp = "active|disabled|deprecated") String status
) {
}
