package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

/**
 * 表示Dashboard智能体Performance相关的领域对象。
 * Agent/version execution performance projection. */
@Data
public class DashboardAgentPerformanceRow {

    private Long agentId;
    private String agentName;
    private Integer versionNo;
    private Long calls;
    private Long succeededCalls;
    private Double averageLatencyMs;
}
