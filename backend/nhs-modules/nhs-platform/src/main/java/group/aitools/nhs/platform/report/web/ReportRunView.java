package group.aitools.nhs.platform.report.web;

import group.aitools.nhs.platform.report.domain.AgentReportRun;

import java.time.LocalDateTime;

/**
 * 封装报表Run相关的不可变数据。
 */
public record ReportRunView(
    Long id,
    Long reportId,
    String triggerType,
    String resolvedParamsJson,
    String executedSql,
    Long resultArtifactId,
    String resultHash,
    Long rowCount,
    String status,
    String errorSummary,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    public static ReportRunView from(AgentReportRun run) {
        return new ReportRunView(
            run.getId(), run.getReportId(), run.getTriggerType(), run.getResolvedParamsJson(),
            run.getExecutedSql(), run.getResultArtifactId(), run.getResultHash(), run.getRowCount(), run.getStatus(),
            run.getErrorSummary(), run.getStartedAt(), run.getFinishedAt(), run.getCreatedAt()
        );
    }
}
