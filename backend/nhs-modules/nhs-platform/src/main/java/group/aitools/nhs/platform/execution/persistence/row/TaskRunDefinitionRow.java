package group.aitools.nhs.platform.execution.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示任务Run定义相关的领域对象。
 * Joined task and Agent version facts required to freeze a new run. */
@Data
public class TaskRunDefinitionRow {

    private Long taskId;
    private Long taskVersionId;
    private Long projectId;
    private Long ownerId;
    private String ownerPrincipalType;
    private Long sourceConversationId;
    private Long latestRunId;
    private String latestRunStatus;
    private String taskStatus;
    private String orchestrationMode;
    private String taskTitle;
    private String taskObjective;
    private LocalDateTime startAt;
    private String taskVersionTitle;
    private String taskVersionObjective;
    private String taskContextSnapshotJson;
    private String taskResourceSnapshotJson;
    private String taskAcceptanceSnapshotJson;
    private String taskInputSnapshotJson;
    private String taskContentHash;
    private String taskBudgetJson;
    private Long workflowVersionId;
    private Long agentVersionId;
    private Long agentId;
    private String agentKey;
    private String agentName;
    private String agentStatus;
    private String agentVersionStatus;
    private LocalDateTime agentPublishedAt;
    private String systemPrompt;
    private Long modelId;
    private Long synthesisModelId;
    private String agentRuntimeConfigJson;
    private String agentWelcomeConfigJson;
    private String agentRoutingTagsJson;
    private String agentContentHash;
}
