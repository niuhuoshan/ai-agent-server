package group.aitools.nhs.platform.audit.web;

import java.util.List;

/**
 * 封装审计Feature相关的不可变数据。
 * Available values for audit filters, sourced from persisted events. */
public record AuditFeatureView(
    List<String> actorTypes,
    List<String> actions,
    List<String> resourceTypes,
    List<String> decisions
) {
}
