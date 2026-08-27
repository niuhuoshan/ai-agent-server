package group.aitools.nhs.platform.audit.web;

import java.util.List;

/**
 * 封装审计Page相关的不可变数据。
 */
public record AuditPageView(
    long total,
    int page,
    int size,
    List<AuditEventView> items,
    AuditStatisticsView statistics
) {
}
