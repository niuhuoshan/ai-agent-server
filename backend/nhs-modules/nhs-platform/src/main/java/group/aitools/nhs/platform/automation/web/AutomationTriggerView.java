package group.aitools.nhs.platform.automation.web;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装自动化Trigger相关的不可变数据。
 */
public record AutomationTriggerView(
    Long id,
    String triggerKey,
    String name,
    String triggerType,
    Long taskId,
    Long taskVersionId,
    Long taskRevisionNo,
    Long serviceAccountId,
    String cronExpression,
    String timezone,
    String status,
    String misfirePolicy,
    Integer maxCatchupCount,
    Integer maxAttempts,
    String inputTemplate,
    LocalDateTime lastRunAt,
    LocalDateTime nextRunAt,
    Long revisionNo,
    Map<String, Object> config,
    LocalDateTime createdAt
) {
}
