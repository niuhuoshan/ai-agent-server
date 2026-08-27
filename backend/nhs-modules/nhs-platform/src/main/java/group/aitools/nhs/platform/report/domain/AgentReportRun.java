package group.aitools.nhs.platform.report.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体报表Run相关的领域对象。
 * Durable execution fact for a saved report. */
@Data
public class AgentReportRun {

    private Long id;
    private Long reportId;
    private Long runId;
    private String triggerType;
    private String resolvedParamsJson;
    private String executedSql;
    private Long resultArtifactId;
    private String resultHash;
    private Long rowCount;
    private String status;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
