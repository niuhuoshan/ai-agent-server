package group.aitools.nhs.platform.nhs.portal.chatbi;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BISqlRepairAttempt相关的领域对象。
 * Persistent, owner-scoped record of one bounded ChatBI SQL repair attempt. */
@Data
public class AgentChatBISqlRepairAttempt {

    private Long id;
    private Long ownerId;
    private Long conversationId;
    private Long datasetId;
    private String traceId;
    private Long failedQueryId;
    private Long retryQueryId;
    private Integer attemptNo;
    private Integer maxAttempts;
    private String errorCategory;
    private String errorSummary;
    private String failedSql;
    private String repairedSql;
    private Long repairModelId;
    private String repairReason;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
