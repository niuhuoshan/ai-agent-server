package group.aitools.nhs.platform.risk.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 封装Update风险策略Status相关的不可变数据。
 */
public record UpdateRiskPolicyStatusRequest(
    @NotBlank @Pattern(regexp = "active|disabled") String status
) {
}
