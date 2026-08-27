package group.aitools.nhs.platform.nhs.portal.chatbi;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BI任务PlanItem相关的领域对象。
 * Persistent node in a ChatBI task plan. */
@Data
public class AgentChatBITaskPlanItem {

    private Long id;
    private Long planId;
    private String taskKey;
    private Integer sequenceNo;
    private String operation;
    private String queryText;
    private String dependsOnJson;
    private String status;
    private String traceId;
    private Long resultQueryId;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
