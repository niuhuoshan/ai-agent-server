package group.aitools.nhs.platform.audit.web;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装审计事件Detail相关的不可变数据。
 * Administrator detail projection; payloads are bounded and secrets are redacted. */
public record AuditEventDetailView(
    Long id,
    String traceId,
    String actorType,
    Long actorId,
    String action,
    String resourceType,
    Long resourceId,
    Long taskId,
    Long runId,
    String permissionProfileVersion,
    String decision,
    String decisionReason,
    Map<String, Object> dataScope,
    String requestSummary,
    String resultSummary,
    String ipAddress,
    String userAgent,
    Map<String, Object> metadata,
    LocalDateTime createdAt
) {
}
