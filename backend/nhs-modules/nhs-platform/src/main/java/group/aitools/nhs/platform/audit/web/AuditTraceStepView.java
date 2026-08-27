package group.aitools.nhs.platform.audit.web;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装审计链路追踪Step相关的不可变数据。
 * Sanitized semantic execution step used by the administrator trace viewer. */
public record AuditTraceStepView(
    int stepNumber,
    String eventId,
    Long conversationId,
    Long runId,
    Long stepId,
    long cursor,
    String eventType,
    String eventStatus,
    String sensitiveLevel,
    String agentName,
    String model,
    String toolName,
    String summary,
    Double executionTimeMs,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    String spanId,
    String parentSpanId,
    Map<String, Object> metadata,
    LocalDateTime occurredAt
) {
}
