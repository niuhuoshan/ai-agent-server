package group.aitools.nhs.platform.audit.web;

import group.aitools.nhs.platform.audit.domain.AgentAuditEvent;

import java.time.LocalDateTime;

/**
 * 封装审计事件相关的不可变数据。
 * Bounded audit projection with no raw metadata, headers, payload or data-scope snapshot. */
public record AuditEventView(
    Long id,
    String traceId,
    String actorType,
    Long actorId,
    String action,
    String resourceType,
    Long resourceId,
    Long taskId,
    Long runId,
    String decision,
    String decisionReason,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    public static AuditEventView from(AgentAuditEvent event) {
        return new AuditEventView(
            event.getId(), event.getTraceId(), event.getActorType(), event.getActorId(),
            event.getAction(), event.getResourceType(), event.getResourceId(), event.getTaskId(),
            event.getRunId(), event.getDecision(), event.getDecisionReason(),
            event.getCreatedAt()
        );
    }
}
