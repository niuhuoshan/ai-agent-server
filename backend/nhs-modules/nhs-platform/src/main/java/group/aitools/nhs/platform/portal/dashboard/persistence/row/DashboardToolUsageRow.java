package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

/**
 * 表示Dashboard工具Usage相关的领域对象。
 * Tool-step usage grouped by the persisted tool identity. */
@Data
public class DashboardToolUsageRow {

    private Long toolId;
    private String toolName;
    private Long invocationCount;
}
