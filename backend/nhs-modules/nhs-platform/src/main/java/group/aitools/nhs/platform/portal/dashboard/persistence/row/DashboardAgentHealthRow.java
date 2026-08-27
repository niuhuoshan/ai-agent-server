package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

/**
 * 表示Dashboard智能体健康状态相关的领域对象。
 * Aggregate Agent/Tool step health facts. */
@Data
public class DashboardAgentHealthRow {

    private Long totalSteps;
    private Long succeededSteps;
    private Long toolCalls;
    private Double averageLatencyMs;
}
