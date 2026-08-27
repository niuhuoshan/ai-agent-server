package group.aitools.nhs.platform.operations.web;

import java.util.Map;

/**
 * 封装系统ComponentTest相关的不可变数据。
 */
public record SystemComponentTestView(
    String component,
    String status,
    String message,
    long latencyMs,
    Map<String, Object> details
) {
}
