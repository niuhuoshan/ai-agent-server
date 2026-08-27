package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

/**
 * 表示Dashboard令牌Totals相关的领域对象。
 * Token totals read from durable conversation message usage columns. */
@Data
public class DashboardTokenTotalsRow {

    private Long messageCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
}
