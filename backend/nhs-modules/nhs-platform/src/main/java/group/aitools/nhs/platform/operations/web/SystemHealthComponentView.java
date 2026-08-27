package group.aitools.nhs.platform.operations.web;

import java.util.Map;

/**
 * 封装系统健康状态Component相关的不可变数据。
 * One independently sampled infrastructure or runtime component. */
public record SystemHealthComponentView(
    String key,
    String name,
    String status,
    boolean critical,
    String message,
    long responseTimeMs,
    Map<String, Object> details
) {
}
