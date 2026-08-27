package group.aitools.nhs.platform.scenario.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体ScenarioUninstallRun相关的领域对象。
 * Durable record for a scenario instance uninstall/disable operation. */
@Data
public class AgentScenarioUninstallRun {
    private Long id;
    private Long instanceId;
    private String templateKey;
    private String idempotencyKey;
    private String status;
    private String reason;
    private String previousStatus;
    private String agentStatus;
    private String warning;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
