package group.aitools.nhs.platform.operations.web;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装{@code LogMaintenanceRun}相关的不可变数据。
 */
public record LogMaintenanceRunView(
    String runId,
    String triggerType,
    String status,
    int retentionDays,
    int policyRevision,
    LocalDateTime cutoffAt,
    String requestedBy,
    LocalDateTime confirmationExpiresAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Map<String, Object> summary,
    String errorCode,
    String errorMessage,
    LocalDateTime createdAt
) {
}
