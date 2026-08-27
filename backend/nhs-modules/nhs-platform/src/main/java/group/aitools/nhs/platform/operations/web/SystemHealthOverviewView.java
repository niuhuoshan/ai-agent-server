package group.aitools.nhs.platform.operations.web;

import java.time.Instant;
import java.util.List;

/**
 * 封装系统健康状态Overview相关的不可变数据。
 * Point-in-time system health snapshot with no credentials or endpoint addresses. */
public record SystemHealthOverviewView(
    String status,
    Instant checkedAt,
    String applicationName,
    String applicationVersion,
    SystemRuntimeMetricsView runtime,
    List<SystemHealthComponentView> components
) {
}
