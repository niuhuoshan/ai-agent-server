package group.aitools.nhs.platform.execution.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示工作流智能体定义相关的领域对象。
 * Published Agent version facts used to freeze one fixed-workflow role. */
@Data
public class WorkflowAgentDefinitionRow {

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
