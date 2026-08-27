package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Dashboard令牌Trend相关的领域对象。
 * Daily token usage projection. */
@Data
public class DashboardTokenTrendRow {

    private LocalDateTime dayBucket;
    private Long calls;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
}
