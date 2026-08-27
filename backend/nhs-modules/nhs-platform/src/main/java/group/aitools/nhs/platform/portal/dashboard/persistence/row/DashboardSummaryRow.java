package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

/**
 * 表示{@code DashboardSummary}相关的领域对象。
 * Aggregate execution facts used by the Nhs dashboard compatibility view. */
@Data
public class DashboardSummaryRow {

    private Long totalRuns;
    private Long succeededRuns;
    private Long failedRuns;
    private Long cancelledRuns;
    private Double averageLatencyMs;
}
