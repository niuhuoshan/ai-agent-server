package group.aitools.nhs.platform.scenario.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体ScenarioInstallRun相关的领域对象。
 * A precheck/install attempt, retained for idempotency and operational audit. */
@Data
public class AgentScenarioInstallRun {
    private Long id;
    private Long instanceId;
    private String templateKey;
    private String idempotencyKey;
    private String status;
    private String precheckJson;
    private String resourceBindingsJson;
    private String errorSummary;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
