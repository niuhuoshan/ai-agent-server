package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示{@code DashboardRecentRun}相关的领域对象。
 * Recent task-run projection used instead of Nhs's legacy HTTP access log. */
@Data
public class DashboardRecentRunRow {

    private Long runId;
    private Long taskId;
    private Long createdBy;
    private String traceId;
    private String status;
    private String taskTitle;
    private String agentName;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
