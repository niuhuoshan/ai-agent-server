package group.aitools.nhs.platform.audit.web;

import java.util.List;

/**
 * 封装审计链路追踪Spans相关的不可变数据。
 * Flat span projection retaining parent IDs for tree rendering without exposing raw payloads. */
public record AuditTraceSpansView(
    String traceId,
    List<AuditTraceStepView> spans
) {
}
