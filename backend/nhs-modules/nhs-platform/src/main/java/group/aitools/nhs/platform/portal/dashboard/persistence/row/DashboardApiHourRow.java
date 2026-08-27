package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Dashboard接口Hour相关的领域对象。
 * Hourly machine API call trend projection. */
@Data
public class DashboardApiHourRow {

    private LocalDateTime hourBucket;
    private Long totalCalls;
    private Long succeededCalls;
    private Long errorCalls;
    private Double averageDurationMs;
}
