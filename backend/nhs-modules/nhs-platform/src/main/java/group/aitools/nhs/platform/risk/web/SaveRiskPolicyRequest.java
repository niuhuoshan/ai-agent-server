package group.aitools.nhs.platform.risk.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 封装Save风险策略相关的不可变数据。
 * Typed policy payload; no free-form configuration document is accepted. */
public record SaveRiskPolicyRequest(
    @NotBlank @Pattern(regexp = "[a-z][a-z0-9._-]{0,127}") String policyKey,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{0,63}") String resourceType,
    @NotBlank @Pattern(regexp = "[a-z*][a-z0-9_*.-]{0,63}") String action,
    @NotBlank @Pattern(regexp = "R0|R1|R2|R3") String riskLevel,
    @NotBlank @Pattern(regexp = "allow|approval_required|deny") String disposition,
    @Size(max = 64) String approvalRole,
    @NotNull Boolean notifyEnabled,
    @NotNull @Min(0) @Max(9999) Integer priority,
    @Size(max = 500) String description,
    @NotBlank @Pattern(regexp = "active|disabled") String status
) {
}
