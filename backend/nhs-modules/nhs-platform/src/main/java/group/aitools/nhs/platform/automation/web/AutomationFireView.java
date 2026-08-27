package group.aitools.nhs.platform.automation.web;

import java.time.LocalDateTime;

/**
 * 封装自动化Fire相关的不可变数据。
 */
public record AutomationFireView(
    Long id,
    Long triggerId,
    String sourceType,
    String status,
    Long jobId,
    Long runId,
    Integer attemptNo,
    String lastError,
    LocalDateTime scheduledAt,
    LocalDateTime acceptedAt,
    LocalDateTime dispatchedAt,
    boolean replayed
) {
}
