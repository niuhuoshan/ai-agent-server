package group.aitools.nhs.platform.nhs.portal.chatbi;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BI任务Plan相关的领域对象。
 * Persistent header for a dependency-aware ChatBI task plan. */
@Data
public class AgentChatBITaskPlan {

    private Long id;
    private String planKey;
    private Long ownerId;
    private Long conversationId;
    private Long datasetId;
    private String requestQuestion;
    private String status;
    private Integer taskCount;
    private String currentTaskKey;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
