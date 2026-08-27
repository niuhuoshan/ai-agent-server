package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Dashboard接口Trend相关的领域对象。
 * Daily machine API call trend projection. */
@Data
public class DashboardApiTrendRow {

    private LocalDateTime dayBucket;
    private Long totalCalls;
    private Long succeededCalls;
    private Long errorCalls;
    private Double averageDurationMs;
}
