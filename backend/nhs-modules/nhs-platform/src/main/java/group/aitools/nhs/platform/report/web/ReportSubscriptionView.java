package group.aitools.nhs.platform.report.web;

import group.aitools.nhs.platform.report.domain.AgentReportSubscription;

import java.time.LocalDateTime;

/**
 * 封装报表Subscription相关的不可变数据。
 */
public record ReportSubscriptionView(
    Long id,
    Long reportId,
    /** Legacy compatibility field. Report subscriptions no longer bind task automation triggers. */
    Long triggerId,
    String scheduleType,
    String cronExpr,
    Integer intervalMinutes,
    String timezone,
    String paramsJson,
    String notifyPolicyJson,
    String status,
    Integer maxAttempts,
    LocalDateTime lastRunAt,
    LocalDateTime nextRunAt,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param subscription {@code subscription}参数
     * @return 处理结果
     */
    public static ReportSubscriptionView from(AgentReportSubscription subscription) {
        return new ReportSubscriptionView(
            subscription.getId(), subscription.getReportId(), null,
            subscription.getScheduleType(), subscription.getCronExpr(), subscription.getIntervalMinutes(),
            subscription.getTimezone(), subscription.getParamsJson(), subscription.getNotifyPolicyJson(),
            subscription.getStatus(), subscription.getMaxAttempts(), subscription.getLastRunAt(),
            subscription.getNextRunAt(),
            subscription.getCreateTime()
        );
    }

    /**
     * 创建 {@code ReportSubscriptionView} 实例并初始化所需依赖。
     *
     * @param id 资源标识
     * @param reportId 资源标识
     * @param triggerId 资源标识
     * @param timezone {@code timezone}参数
     * @param paramsJson {@code paramsJson}参数
     * @param notifyPolicyJson notify策略Json参数
     * @param status 目标状态
     * @param lastRunAt {@code lastRunAt}参数
     * @param nextRunAt {@code nextRunAt}参数
     * @param createdAt {@code createdAt}参数
     */
    public ReportSubscriptionView(
        Long id,
        Long reportId,
        Long triggerId,
        String timezone,
        String paramsJson,
        String notifyPolicyJson,
        String status,
        LocalDateTime lastRunAt,
        LocalDateTime nextRunAt,
        LocalDateTime createdAt
    ) {
        this(
            id, reportId, triggerId, null, null, null, timezone, paramsJson, notifyPolicyJson,
            status, null, lastRunAt, nextRunAt, createdAt
        );
    }
}
