package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

/**
 * 表示Dashboard令牌智能体相关的领域对象。
 * Token totals grouped by Agent. */
@Data
public class DashboardTokenAgentRow {

    private Long agentId;
    private String agentName;
    private Long calls;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
}
