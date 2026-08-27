package group.aitools.nhs.platform.audit.web;

/**
 * 封装审计统计相关的不可变数据。
 * Bounded aggregate for the currently selected audit window and filters. */
public record AuditStatisticsView(
    long total,
    long allowCount,
    long denyCount,
    long approvalRequiredCount,
    long successCount,
    long failureCount,
    long distinctActors,
    long distinctTraces
) {

    /**
     * 处理{@code successRate}并返回对应结果。
     *
     * @return 处理结果
     */
    public double successRate() {
        long completed = successCount + failureCount;
        return completed == 0 ? 0D : Math.round(successCount * 10_000D / completed) / 100D;
    }
}
