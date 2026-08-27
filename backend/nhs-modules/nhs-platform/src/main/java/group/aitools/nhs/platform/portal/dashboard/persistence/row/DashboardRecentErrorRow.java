package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示{@code DashboardRecentError}相关的领域对象。
 * Failed-step projection; error summaries are already bounded by the query service. */
@Data
public class DashboardRecentErrorRow {

    private Long runId;
    private Long taskId;
    private String traceId;
    private String agentName;
    private String stepKey;
    private String errorSummary;
    private LocalDateTime createdAt;
}
