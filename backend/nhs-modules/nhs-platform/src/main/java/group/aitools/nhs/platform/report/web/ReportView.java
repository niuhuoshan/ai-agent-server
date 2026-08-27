package group.aitools.nhs.platform.report.web;

import group.aitools.nhs.platform.report.domain.AgentReport;

import java.time.LocalDateTime;

/**
 * 封装报表相关的不可变数据。
 */
public record ReportView(
    Long id,
    String reportKey,
    String name,
    Long datasetId,
    String sqlTemplate,
    String paramsSchemaJson,
    String visibility,
    Long ownerId,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param report 报表参数
     * @return 处理结果
     */
    public static ReportView from(AgentReport report) {
        return new ReportView(
            report.getId(), report.getReportKey(), report.getName(), report.getDatasetId(),
            report.getSqlTemplate(), report.getParamsSchemaJson(), report.getVisibility(),
            report.getOwnerId(), report.getStatus(), report.getCreateTime(), report.getUpdateTime()
        );
    }
}
