package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Dashboard接口Summary相关的领域对象。
 * Aggregated machine API call facts for one dashboard scope and period. */
@Data
public class DashboardApiSummaryRow {

    private Long totalCalls;
    private Long succeededCalls;
    private Long errorCalls;
    private Double averageDurationMs;
    private LocalDateTime lastCallAt;
}
