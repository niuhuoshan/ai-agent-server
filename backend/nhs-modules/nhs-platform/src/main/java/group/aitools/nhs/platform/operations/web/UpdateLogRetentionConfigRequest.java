package group.aitools.nhs.platform.operations.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 封装{@code UpdateLogRetentionConfig}相关的不可变数据。
 */
public record UpdateLogRetentionConfigRequest(
    @JsonAlias("audit_log_retention_days") @Min(1) @Max(3650) int retentionDays,
    @Min(1) Integer expectedRevision,
    @Size(min = 2, max = 500) String changeReason
) {
}
