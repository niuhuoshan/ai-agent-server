package group.aitools.nhs.platform.operations.web;

import java.util.Map;

/**
 * 封装系统DiagnosticCheck相关的不可变数据。
 * One actionable private-deployment diagnostic check. */
public record SystemDiagnosticCheckView(
    String key,
    String name,
    String status,
    boolean required,
    String message,
    Map<String, Object> metrics,
    String remediation
) {
}
