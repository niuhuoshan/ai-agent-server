package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.platform.execution.web.ExecutionEventView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 提供NhsSse事件相关的数据访问能力。
 *
 * Projects durable platform events into the Nhs chat stream contract.
 *
 * The platform event remains the source of truth.  This adapter deliberately
 * keeps the cursor and correlation identifiers on every compatible chunk so a
 * reconnecting client can resume without inventing a second runtime stream.
 */
public final class NhsSseEventMapper {

    private static final Set<String> CUSTOM_TYPES = Set.of(
        "retraction", "agent_reply", "model_call", "thinking", "reasoning_content",
        "context_compression", "context_update", "log", "tool_result_data",
        "permission_required", "permission_result", "external_execution_required",
        "external_execution_result", "business_confirmation", "agent_handoff",
        "chatbi_task_plan", "chatbi_task_status", "chatbi_insight_meta",
        "chatbi_metadata_guide", "citation", "skill", "router_log", "meta", "debug", "error"
    );

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param event 事件参数
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> map(ExecutionEventView event) {
        if (event == null) return List.of();
        Map<String, Object> payload = merge(event.payload(), event.projection());
        String eventType = normalize(event.eventType());
        return switch (eventType) {
            case "run_started" -> List.of(chunk(event, "agent_reply", Map.of(
                "phase", "start",
                "reply_id", text(payload, "replyId", event.eventId()),
                "session_id", text(payload, "sessionId", event.conversationId()),
                "agent_name", text(payload, "agentName", "")
            )));
            case "run_finished" -> List.of(chunk(event, "agent_reply", Map.of(
                "phase", "end",
                "reply_id", text(payload, "replyId", event.eventId()),
                "session_id", text(payload, "sessionId", event.conversationId())
            )));
            case "model_call_started" -> List.of(chunk(event, "model_call", Map.of(
                "phase", "start",
                "reply_id", text(payload, "replyId", event.eventId()),
                "model_name", text(payload, "model", "")
            )));
            case "model_call_finished" -> List.of(chunk(event, "model_call", values(
                "phase", "end",
                "reply_id", text(payload, "replyId", event.eventId()),
                "model_name", text(payload, "model", ""),
                "input_tokens", number(payload, "promptTokens"),
                "output_tokens", number(payload, "completionTokens"),
                "cached_tokens", number(payload, "cachedTokens"),
                "total_tokens", number(payload, "totalTokens"),
                "duration_ms", number(payload, "durationMs")
            )));
            case "text_delta" -> List.of(chunk(event, null, Map.of("content", event.summary() == null ? "" : event.summary())));
            case "thinking_started" -> List.of(chunk(event, "thinking", Map.of(
                "phase", "start", "block_id", text(payload, "blockId", event.eventId()),
                "reply_id", text(payload, "replyId", event.eventId())
            )));
            case "thinking_delta" -> List.of(chunk(event, "reasoning_content", Map.of(
                "content", text(payload, "delta", text(payload, "content", "")),
                "reply_id", text(payload, "replyId", event.eventId())
            )));
            case "thinking_finished" -> List.of(chunk(event, "thinking", Map.of(
                "phase", "end", "block_id", text(payload, "blockId", event.eventId()),
                "reply_id", text(payload, "replyId", event.eventId())
            )));
            case "tool_call_started", "tool_call_delta", "tool_call_finished" ->
                List.of(toolCall(event, payload, eventType));
            case "tool_result_started", "tool_result_delta", "tool_result_finished" ->
                toolResult(event, payload, eventType);
            case "approval_required" -> List.of(approval(event, payload));
            case "approval_resolved" -> List.of(chunk(event, "permission_result", values(
                "permission_request_id", text(payload, "permissionRequestId", event.eventId()),
                "status", text(payload, "status", event.eventStatus())
            )));
            case "external_execution_required" -> List.of(interrupt(event, payload, "external_execution_required"));
            case "external_execution_resolved" -> List.of(chunk(event, "external_execution_result", values(
                "external_execution_request_id", text(payload, "externalExecutionRequestId", event.eventId()),
                "status", text(payload, "status", event.eventStatus()),
                "result", payload.getOrDefault("result", payload.get("output"))
            )));
            case "subagent_event" -> List.of(chunk(event, "agent_handoff", values(
                "version", 1,
                "source_agent", text(payload, "sourceAgent", text(payload, "agentName", "")),
                "target_agent", text(payload, "targetAgent", text(payload, "targetAgentName", "")),
                "reason", text(payload, "reason", event.summary()),
                "phase", text(payload, "phase", "event")
            )));
            case "permission_denied" -> List.of(error(event, "permission_denied", false, "permission"));
            case "iteration_limit_reached" -> List.of(error(event, "iteration_limit_reached", true, "runtime"));
            case "cancelled" -> List.of(error(event, "cancelled", true, "runtime"));
            case "failed" -> List.of(error(event, text(payload, "errorCode", text(payload, "errorType", "runtime_error")),
                Boolean.TRUE.equals(payload.get("retryable")), text(payload, "phase", "runtime")));
            case "custom" -> custom(event, payload);
            default -> List.of(chunk(event, "meta", values(
                "event_type", eventType,
                "summary", event.summary(),
                "status", event.eventStatus()
            )));
        };
    }

    /**
     * 将输入数据转换为{@code olCall}。
     *
     * @param event 事件参数
     * @param payload {@code payload}参数
     * @param eventType 业务类型
     * @return 处理结果
     */
    private Map<String, Object> toolCall(ExecutionEventView event, Map<String, Object> payload, String eventType) {
        String status = eventType.endsWith("finished") ? "success" : "pending";
        return chunk(event, "log", values(
            "id", text(payload, "toolCallId", event.eventId()),
            "title", "调用工具: " + text(payload, "toolName", "工具"),
            "details", event.summary(),
            "status", status,
            "category", "tool",
            "tool_call_id", text(payload, "toolCallId", event.eventId()),
            "tool_name", text(payload, "toolName", "")
        ));
    }

    /**
     * 将输入数据转换为ol结果。
     *
     * @param event 事件参数
     * @param payload {@code payload}参数
     * @param eventType 业务类型
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> toolResult(
        ExecutionEventView event, Map<String, Object> payload, String eventType
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Object citations = payload.get("citations");
        if (citations instanceof List<?> values && !values.isEmpty()) {
            return List.of(chunk(event, "citation", values(
                "tool_call_id", text(payload, "toolCallId", event.eventId()),
                "data", citations,
                "citation_count", number(payload, "citationCount"),
                "evidence_type", text(payload, "evidenceType", "internal_knowledge"),
                "evidence_status", text(payload, "evidenceStatus", "verified")
            )));
        }
        if (payload.containsKey("outputData") || payload.containsKey("data") || payload.containsKey("url")) {
            return List.of(chunk(event, "tool_result_data", values(
                "tool_call_id", text(payload, "toolCallId", event.eventId()),
                "block_id", text(payload, "blockId", event.eventId()),
                "media_type", text(payload, "mediaType", ""),
                "data", payload.getOrDefault("outputData", payload.get("data")),
                "url", payload.get("url"),
                "evidence_type", text(payload, "evidenceType", ""),
                "evidence_status", text(payload, "evidenceStatus", "")
            )));
        }
        boolean batchDelegation = "sub_agent_batch_call".equals(text(payload, "toolName", ""));
        String status = eventType.endsWith("finished")
            ? batchDelegation
                ? text(payload, "resultStatus", text(payload, "toolState", event.eventStatus()))
                : text(payload, "toolState", event.eventStatus())
            : "pending";
        String delegationStatus = text(payload, "delegationStatus", null);
        return List.of(chunk(event, "log", values(
            "id", text(payload, "toolCallId", event.eventId()),
            "title", "工具结果",
            "details", text(payload, "resultMessage", event.summary()),
            "status", status,
            "category", "tool",
            "tool_call_id", text(payload, "toolCallId", event.eventId()),
            "tool_name", text(payload, "toolName", ""),
            "delegation_status", delegationStatus,
            "delegation_count", number(payload, "delegationCount"),
            "delegation_completed_count", number(payload, "delegationCompletedCount"),
            "delegation_failed_count", number(payload, "delegationFailedCount"),
            "delegation_pending_count", number(payload, "delegationPendingCount"),
            "delegation_results", payload.get("delegationResults"),
            "evidence_type", text(payload, "evidenceType", ""),
            "evidence_status", text(payload, "evidenceStatus", "")
        )));
    }

    /**
     * 处理审批并返回对应结果。
     *
     * @param event 事件参数
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    private Map<String, Object> approval(ExecutionEventView event, Map<String, Object> payload) {
        Object business = payload.get("businessConfirmation");
        if (business != null) {
            return chunk(event, "business_confirmation", values(
                "data", business,
                "confirmation_id", text(payload, "confirmationId", event.eventId())
            ));
        }
        return chunk(event, "permission_required", values(
            "permission_request_id", text(payload, "permissionRequestId", event.eventId()),
            "reply_id", text(payload, "replyId", event.eventId()),
            "title", text(payload, "title", event.summary()),
            "details", text(payload, "details", event.summary()),
            "tool_call", payload.get("toolCall")
        ));
    }

    /**
     * 处理{@code interrupt}并返回对应结果。
     *
     * @param event 事件参数
     * @param payload {@code payload}参数
     * @param type 业务类型
     * @return 处理结果
     */
    private Map<String, Object> interrupt(ExecutionEventView event, Map<String, Object> payload, String type) {
        return chunk(event, type, values(
            "external_execution_request_id", text(payload, "externalExecutionRequestId", event.eventId()),
            "permission_request_id", text(payload, "permissionRequestId", event.eventId()),
            "reply_id", text(payload, "replyId", event.eventId()),
            "title", text(payload, "title", event.summary()),
            "details", text(payload, "details", event.summary()),
            "tool_call", payload.get("toolCall")
        ));
    }

    /**
     * 处理{@code custom}并返回对应结果。
     *
     * @param event 事件参数
     * @param payload {@code payload}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> custom(ExecutionEventView event, Map<String, Object> payload) {
        if (Boolean.TRUE.equals(payload.get("retraction"))) {
            return List.of(chunk(event, "retraction", values(
                "code", text(payload, "code", "output_retracted"),
                "message", event.summary()
            )));
        }
        String type = normalize(text(payload, "type", text(payload, "eventType", "meta")));
        if (!CUSTOM_TYPES.contains(type)) type = "meta";
        Map<String, Object> result = new LinkedHashMap<>(payload);
        result.remove("type");
        result.remove("eventType");
        return List.of(chunk(event, type, result));
    }

    /**
     * 处理{@code error}并返回对应结果。
     *
     * @param event 事件参数
     * @param code {@code code}参数
     * @param retryable {@code retryable}参数
     * @param phase {@code phase}参数
     * @return 处理结果
     */
    private Map<String, Object> error(ExecutionEventView event, String code, boolean retryable, String phase) {
        return chunk(event, "error", values(
            "code", code,
            "message", event.summary(),
            "retryable", retryable,
            "phase", phase,
            "provider_status", event.eventStatus()
        ));
    }

    /**
     * 处理{@code chunk}并返回对应结果。
     *
     * @param event 事件参数
     * @param type 业务类型
     * @param values {@code values}参数
     * @return 处理结果
     */
    private Map<String, Object> chunk(ExecutionEventView event, String type, Map<String, Object> values) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> result = new LinkedHashMap<>();
        if (type != null && !type.isBlank()) result.put("type", type);
        result.put("event_id", event.eventId());
        result.put("cursor", event.cursor());
        result.put("trace_id", event.traceId());
        if (event.conversationId() != null) result.put("conversation_id", event.conversationId());
        if (event.runId() != null) result.put("run_id", event.runId());
        if (event.stepId() != null) result.put("step_id", event.stepId());
        if (event.eventStatus() != null) result.put("event_status", event.eventStatus());
        values.forEach((key, value) -> {
            if (value != null) result.put(key, value);
        });
        return Map.copyOf(result);
    }

    /**
     * 处理{@code merge}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @param projection {@code projection}参数
     * @return 处理结果
     */
    private Map<String, Object> merge(Map<String, Object> payload, Map<String, Object> projection) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (payload != null) result.putAll(payload);
        if (projection != null) result.putAll(projection);
        return result;
    }

    /**
     * 处理{@code values}并返回对应结果。
     *
     * @param pairs {@code pairs}参数
     * @return 处理结果
     */
    private Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i] != null && pairs[i + 1] != null) result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return result;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param key {@code key}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String text(Map<String, Object> values, String key, Object fallback) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) return fallback == null ? "" : String.valueOf(fallback);
        return String.valueOf(value);
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private Number number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number : null;
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
