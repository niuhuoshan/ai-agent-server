package group.aitools.nhs.platform.audit.web;

import java.util.List;

/**
 * 封装审计链路追踪相关的不可变数据。
 */
public record AuditTraceView(
    String traceId,
    int totalSteps,
    List<AuditTraceStepView> steps
) {
}
