package group.aitools.nhs.runtime.spi;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/**
 * 封装运行时相关的不可变数据。
 * Provider-neutral event persisted by the platform before it is exposed through SSE. */
public record RuntimeEvent(
    String sourceEventId,
    RuntimeExecutionKey executionKey,
    Long conversationId,
    Long runId,
    Long stepId,
    RuntimeEventType type,
    RuntimeEventStatus status,
    Instant occurredAt,
    String summary,
    Map<String, Object> payload,
    RuntimeSensitiveLevel sensitiveLevel,
    Map<String, Object> queryProjection
) {

    /**
 * 创建 {@code RuntimeEvent} 实例并初始化所需依赖。
 * Backward-compatible constructor for providers without a safe query projection. */
    public RuntimeEvent(
        String sourceEventId,
        RuntimeExecutionKey executionKey,
        Long conversationId,
        Long runId,
        Long stepId,
        RuntimeEventType type,
        RuntimeEventStatus status,
        Instant occurredAt,
        String summary,
        Map<String, Object> payload,
        RuntimeSensitiveLevel sensitiveLevel
    ) {
        this(
            sourceEventId, executionKey, conversationId, runId, stepId, type, status,
            occurredAt, summary, payload, sensitiveLevel, Map.of()
        );
    }

    /**
     * 创建 {@code RuntimeEvent} 实例并初始化所需依赖。
     *
     * @param sourceEventId 资源标识
     * @param executionKey 执行Key参数
     * @param conversationId 资源标识
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param type 业务类型
     * @param status 目标状态
     * @param occurredAt {@code occurredAt}参数
     * @param summary {@code summary}参数
     * @param payload {@code payload}参数
     * @param sensitiveLevel {@code sensitiveLevel}参数
     * @param queryProjection 查询Projection参数
     */
    public RuntimeEvent {
        if (sourceEventId == null || sourceEventId.isBlank()) {
            throw new IllegalArgumentException("sourceEventId must not be blank");
        }
        sourceEventId = sourceEventId.strip();
        if (sourceEventId.length() > 64) {
            throw new IllegalArgumentException("sourceEventId exceeds 64 characters");
        }
        if (executionKey == null || type == null || status == null || occurredAt == null || sensitiveLevel == null) {
            throw new IllegalArgumentException("runtime event metadata must not be null");
        }
        summary = summary == null ? "" : summary;
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        queryProjection = queryProjection == null
            ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(queryProjection));
    }
}
