package group.aitools.nhs.platform.execution.web;

import group.aitools.nhs.runtime.spi.RuntimeSecretScrubber;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装执行事件相关的不可变数据。
 */
public record ExecutionEventView(
    String eventId,
    String traceId,
    Long conversationId,
    Long runId,
    Long stepId,
    long cursor,
    String eventType,
    String eventStatus,
    String summary,
    Map<String, Object> payload,
    String sensitiveLevel,
    LocalDateTime occurredAt,
    Map<String, Object> projection
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
 * 创建 {@code ExecutionEventView} 实例并初始化所需依赖。
 * Backward-compatible constructor for callers that do not provide a safe projection. */
    public ExecutionEventView(
        String eventId,
        String traceId,
        Long conversationId,
        Long runId,
        Long stepId,
        long cursor,
        String eventType,
        String eventStatus,
        String summary,
        Map<String, Object> payload,
        String sensitiveLevel,
        LocalDateTime occurredAt
    ) {
        this(
            eventId, traceId, conversationId, runId, stepId, cursor, eventType,
            eventStatus, summary, payload, sensitiveLevel, occurredAt, Map.of()
        );
    }

    /**
     * 创建 {@code ExecutionEventView} 实例并初始化所需依赖。
     *
     * @param eventId 资源标识
     * @param traceId 资源标识
     * @param conversationId 资源标识
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param cursor {@code cursor}参数
     * @param eventType 业务类型
     * @param eventStatus 目标状态
     * @param summary {@code summary}参数
     * @param payload {@code payload}参数
     * @param sensitiveLevel {@code sensitiveLevel}参数
     * @param occurredAt {@code occurredAt}参数
     * @param projection {@code projection}参数
     */
    public ExecutionEventView {
        payload = immutable(payload);
        projection = immutable(projection);
    }

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param event 事件参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static ExecutionEventView from(AgentExecutionEvent event, JsonMapper jsonMapper) {
        return from(event, jsonMapper, true, false);
    }

    /**
     * 处理{@code forExternal}并返回对应结果。
     *
     * @param event 事件参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static ExecutionEventView forExternal(AgentExecutionEvent event, JsonMapper jsonMapper) {
        // Delta fragments are deliberately hidden because a secret can be split
        // across two events and cannot be scrubbed without retaining stream state.
        return from(event, jsonMapper, false, false);
    }

    /**
 * 处理for会话Owner并返回对应结果。
 * Owner-authorized conversation projection; sensitive reasoning stays private to that owner. */
    public static ExecutionEventView forConversationOwner(AgentExecutionEvent event, JsonMapper jsonMapper) {
        return from(event, jsonMapper, true, true);
    }

    /**
 * 处理for链路追踪并返回对应结果。
 * Builds the bounded, still-fragmented projection consumed only by Trace aggregation. */
    public static ExecutionEventView forTrace(AgentExecutionEvent event, JsonMapper jsonMapper) {
        return from(event, jsonMapper, true, false);
    }

    /**
 * 处理{@code externalized}并返回对应结果。
 * Masks stream fragments on a view that was produced before the API boundary. */
    public ExecutionEventView externalized() {
        return projection.isEmpty() ? this : new ExecutionEventView(
            eventId, traceId, conversationId, runId, stepId, cursor, eventType,
            eventStatus, summary, payload, sensitiveLevel, occurredAt,
            externalProjection(projection)
        );
    }

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param event 事件参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param exposeDeltaFragments {@code exposeDeltaFragments}参数
     * @param exposeSensitive {@code exposeSensitive}参数
     * @return 处理结果
     */
    private static ExecutionEventView from(
        AgentExecutionEvent event,
        JsonMapper jsonMapper,
        boolean exposeDeltaFragments,
        boolean exposeSensitive
    ) {
        boolean payloadVisible = "public".equals(event.getSensitiveLevel())
            || exposeSensitive && "sensitive".equals(event.getSensitiveLevel());
        Map<String, Object> payload = payloadVisible
            ? parsePayload(event.getPayloadJson(), jsonMapper)
            : Map.of("redacted", true);
        boolean projectionVisible = "public".equals(event.getSensitiveLevel())
            || "internal".equals(event.getSensitiveLevel())
            || exposeSensitive && "sensitive".equals(event.getSensitiveLevel());
        Map<String, Object> projection = projectionVisible
            ? parseProjection(event.getQueryProjectionJson(), jsonMapper) : Map.of();
        if (!exposeDeltaFragments && !projection.isEmpty()) {
            projection = externalProjection(projection);
        }
        return new ExecutionEventView(
            event.getEventId(),
            event.getTraceId(),
            event.getConversationId(),
            event.getRunId(),
            event.getStepId(),
            event.getCursor(),
            event.getEventType(),
            event.getEventStatus(),
            event.getSummary(),
            payload,
            event.getSensitiveLevel(),
            event.getOccurredAt(),
            projection
        );
    }

    /**
     * 处理{@code parsePayload}并返回对应结果。
     *
     * @param payloadJson {@code payloadJson}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    private static Map<String, Object> parsePayload(String payloadJson, JsonMapper jsonMapper) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> payload = jsonMapper.readValue(payloadJson, MAP_TYPE);
            return payload == null ? Map.of() : RuntimeSecretScrubber.sanitizeMap(payload);
        } catch (RuntimeException exception) {
            return Map.of("redacted", true, "invalidPayload", true);
        }
    }

    /**
     * 处理{@code parseProjection}并返回对应结果。
     *
     * @param projectionJson {@code projectionJson}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    private static Map<String, Object> parseProjection(String projectionJson, JsonMapper jsonMapper) {
        if (projectionJson == null || projectionJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> projection = jsonMapper.readValue(projectionJson, MAP_TYPE);
            return projection == null ? Map.of() : RuntimeSecretScrubber.sanitizeMap(projection);
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    /**
     * 处理{@code externalProjection}并返回对应结果。
     *
     * @param projection {@code projection}参数
     * @return 处理结果
     */
    private static Map<String, Object> externalProjection(Map<String, Object> projection) {
        Map<String, Object> safe = new LinkedHashMap<>(projection);
        if (safe.containsKey("inputDelta")) {
            safe.put("inputDelta", "[REDACTED]");
        }
        if (safe.containsKey("outputDelta")) {
            safe.put("outputDelta", "[REDACTED]");
        }
        // Tool result payloads can contain arbitrary business data and may be
        // split across multiple events just like outputDelta.  Keep the
        // existence of the field for schema compatibility, but never expose
        // its value on ordinary event/SSE/embed projections.
        if (safe.containsKey("outputData")) {
            safe.put("outputData", "[REDACTED]");
        }
        // These fields are produced by the runtime's explicit allow-list and
        // contain only UI metadata, never the arbitrary tool result body.
        return safe;
    }

    /**
     * 处理{@code immutable}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null || source.isEmpty()
            ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
