package group.aitools.nhs.platform.approval.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 封装智能体审批操作的请求参数。
 * Durable high-risk action approval with a server-owned pending action snapshot. */
@Data
public class AgentApprovalRequest {

    private Long id;
    private Long taskId;
    private Long runId;
    private Long stepId;
    private Long toolId;
    private String riskLevel;
    private String actionSummary;
    private String inputSummary;
    private String impactScope;
    private String credentialRef;
    private String status;
    private Long requestedBy;
    private Long reviewerId;
    private String reviewComment;
    private LocalDateTime expiresAt;
    private String decisionTokenHash;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private String requestEventId;
    private String replyId;
    private String pendingActionsJson;
    private String decisionMetadataJson;
    private String decisionKeyHash;
}
