package group.aitools.nhs.platform.connector.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体运行时Confirmation相关的领域对象。
 * Durable business-confirmation decision bound to one suspended AgentScope reply. */
@Data
public class AgentRuntimeConfirmation {

    private Long id;
    private String confirmationKey;
    private Long ownerId;
    private String executionId;
    private Long conversationId;
    private Long conversationTurnId;
    private Long taskId;
    private Long runId;
    private Long stepId;
    private Long approvalId;
    private String requestEventId;
    private String replyId;
    private String toolCallId;
    private String toolName;
    private String title;
    private String fieldsJson;
    private String uiJson;
    private String pendingActionsJson;
    private String status;
    private Long reviewerId;
    private String decisionMetadataJson;
    private String decisionKeyHash;
    private LocalDateTime expiresAt;
    private LocalDateTime decidedAt;
    private LocalDateTime consumedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
