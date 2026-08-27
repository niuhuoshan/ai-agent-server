package group.aitools.nhs.platform.connector.web;

import java.time.LocalDateTime;

/**
 * 封装工具OnlineTest相关的不可变数据。
 * Typed outcome envelope; provider failures are never represented as success. */
public record ToolOnlineTestView(
    Long toolId,
    boolean ok,
    Object data,
    String error,
    String status,
    boolean retryable,
    long latencyMs,
    LocalDateTime checkedAt
) {
}
