package group.aitools.nhs.platform.scenario.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体ScenarioInstance相关的领域对象。
 * Durable instance produced by a scenario-template delivery. */
@Data
public class AgentScenarioInstance {
    private Long id;
    private String templateKey;
    private String instanceKey;
    private String displayName;
    private String description;
    private String status;
    private Long ownerId;
    private Long agentId;
    private Long agentVersionId;
    private String resourceBindingsJson;
    private String acceptanceCriteriaJson;
    private String sampleQuestionsJson;
    private String nextStepsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String delFlag;
}
