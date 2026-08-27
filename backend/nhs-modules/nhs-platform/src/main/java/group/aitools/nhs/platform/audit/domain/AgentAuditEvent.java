package group.aitools.nhs.platform.audit.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体审计相关的领域对象。
 * Sanitized audit query row. JSON payload columns are intentionally absent. */
@Data
public class AgentAuditEvent {

    private Long id;
    private String traceId;
    private String actorType;
    private Long actorId;
    private String action;
    private String resourceType;
    private Long resourceId;
    private Long taskId;
    private Long runId;
    private String decision;
    private String decisionReason;
    private String permissionProfileVersion;
    private String dataScopeJson;
    private String requestSummary;
    private String resultSummary;
    private String ipAddress;
    private String userAgent;
    private String metadataJson;
    private LocalDateTime createdAt;
}
