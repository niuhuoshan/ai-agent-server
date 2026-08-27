package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示{@code DashboardHour}相关的领域对象。
 * Hourly execution latency projection. */
@Data
public class DashboardHourRow {

    private LocalDateTime hourBucket;
    private Double averageLatencyMs;
    private Long totalSteps;
}
