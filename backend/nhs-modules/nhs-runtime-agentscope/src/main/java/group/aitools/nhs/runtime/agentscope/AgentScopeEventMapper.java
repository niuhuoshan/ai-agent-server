package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeSecretScrubber;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 提供智能体范围事件相关的数据访问能力。
 * Converts AgentScope events into bounded and redacted platform events. */
public final class AgentScopeEventMapper {

    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int MAX_QUERY_PROJECTION_BYTES = 64 * 1024;
    private static final int MAX_SUMMARY_LENGTH = 512;
    private static final int MAX_CITATIONS = 20;
    private static final int MAX_CITATION_CONTENT_LENGTH = 12 * 1024;
    private static final int MAX_CITATION_TITLE_LENGTH = 512;
    private static final int MAX_CITATION_URL_LENGTH = 2 * 1024;
    private static final Set<String> CITATION_METADATA_FIELDS = Set.of(
        "url", "link", "sourceUrl", "source_url", "page", "pageNo", "page_no",
        "section", "sectionName", "section_name"
    );
    private static final Set<String> FAILED_RESULT_STATUSES = Set.of(
        "failed", "failure", "error", "unavailable", "timeout", "timed_out",
        "provider_error", "transport_error", "query_error", "tool_unavailable",
        "authorization_error", "invalid_arguments", "conflict", "rejected", "denied",
        "cancelled", "expired", "partial_failure"
    );

    private final ObjectMapper objectMapper;

    /**
     * 创建 {@code AgentScopeEventMapper} 实例并初始化所需依赖。
     *
     * @param objectMapper {@code objectMapper}参数
     */
    public AgentScopeEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param request 请求参数
     * @param source 数据源参数
     * @return 处理结果
     */
    public RuntimeEvent map(AgentRunRequest request, AgentEvent source) {
        guardMountedTool(request, source);
        return event(
            source.getId(),
            request.executionKey(),
            request.conversationId(),
            request.runId(),
            request.stepId(),
            type(source),
            status(source),
            occurredAt(source),
            summary(source),
            payload(source),
            sensitivity(source),
            queryProjection(source, ProjectionContext.from(request))
        );
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param request 请求参数
     * @param source 数据源参数
     * @return 处理结果
     */
    public RuntimeEvent map(AgentResumeRequest request, AgentEvent source) {
        guardMountedTool(request.decisionMetadata(), source);
        return event(
            source.getId(),
            request.executionKey(),
            request.conversationId(),
            request.runId(),
            request.stepId(),
            type(source),
            status(source),
            occurredAt(source),
            summary(source),
            payload(source),
            sensitivity(source),
            queryProjection(source, ProjectionContext.from(request))
        );
    }

    /**
     * 处理{@code failure}并返回对应结果。
     *
     * @param request 请求参数
     * @param throwable {@code throwable}参数
     * @return 处理结果
     */
    public RuntimeEvent failure(AgentRunRequest request, Throwable throwable) {
        return failure(
            request.executionKey(), request.conversationId(), request.runId(), request.stepId(),
            request.attributes(), throwable
        );
    }

    /**
     * 处理{@code failure}并返回对应结果。
     *
     * @param request 请求参数
     * @param throwable {@code throwable}参数
     * @return 处理结果
     */
    public RuntimeEvent failure(AgentResumeRequest request, Throwable throwable) {
        return failure(
            request.executionKey(), request.conversationId(), request.runId(), request.stepId(),
            request.decisionMetadata(), throwable
        );
    }

    /**
     * 处理{@code failure}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param conversationId 资源标识
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param attributes {@code attributes}参数
     * @param throwable {@code throwable}参数
     * @return 处理结果
     */
    private RuntimeEvent failure(
        RuntimeExecutionKey key,
        Long conversationId,
        Long runId,
        Long stepId,
        Map<String, Object> attributes,
        Throwable throwable
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorType", throwable.getClass().getName());
        payload.put("message", safeErrorMessage(throwable));
        // Generic runtime/provider failures are safe to replay as a new
        // durable turn. Explicit permission-denied events use their own
        // event type and remain non-retryable in the SSE projection.
        if (throwable instanceof GhostToolInvocationException ghost) {
            payload.put("errorCode", "ghost_tool_blocked");
            payload.put("toolName", ghost.toolName());
            payload.put("retryable", false);
            payload.put("reason", "模型尝试调用当前回合未挂载的工具");
            return event(
                UUID.randomUUID().toString().replace("-", ""), key, conversationId, runId, stepId,
                RuntimeEventType.PERMISSION_DENIED, RuntimeEventStatus.FAILED, Instant.now(),
                "未注册工具调用已拦截: " + ghost.toolName(), payload,
                RuntimeSensitiveLevel.INTERNAL, Map.of()
            );
        }
        payload.put("retryable", true);
        return event(
            UUID.randomUUID().toString().replace("-", ""),
            key,
            conversationId,
            runId,
            stepId,
            RuntimeEventType.FAILED,
            RuntimeEventStatus.FAILED,
            Instant.now(),
            "Agent runtime failed: " + safeErrorMessage(throwable),
            payload,
            RuntimeSensitiveLevel.INTERNAL,
            Map.of()
        );
    }

    /**
     * 处理guardMounted工具相关逻辑。
     *
     * @param request 请求参数
     * @param source 数据源参数
     */
    private void guardMountedTool(AgentRunRequest request, AgentEvent source) {
        guardMountedTool(request.attributes(), source);
    }

    /**
     * 处理guardMounted工具相关逻辑。
     *
     * @param attributes {@code attributes}参数
     * @param source 数据源参数
     */
    private void guardMountedTool(Map<String, Object> attributes, AgentEvent source) {
        if (!(source instanceof ToolCallStartEvent tool)) return;
        Object raw = attributes == null ? null : attributes.get("mountedToolNames");
        if (!(raw instanceof List<?> mounted)) return;
        String toolName = tool.getToolCallName();
        boolean allowed = mounted.stream().anyMatch(value -> value instanceof String name && name.equals(toolName));
        if (!allowed && toolName != null && !toolName.isBlank()) {
            throw new GhostToolInvocationException(toolName);
        }
    }

    /**
     * 表示Ghost工具调用处理过程中发生的业务异常。
     */
    private static final class GhostToolInvocationException extends RuntimeException {
        private final String toolName;

        /**
         * 创建 {@code GhostToolInvocationException} 实例并初始化所需依赖。
         *
         * @param toolName 名称
         */
        private GhostToolInvocationException(String toolName) {
            super("未注册工具调用已拦截: " + toolName);
            this.toolName = toolName;
        }

        /**
         * 将输入数据转换为{@code olName}。
         *
         * @return 处理结果
         */
        private String toolName() {
            return toolName;
        }
    }

    /**
     * 处理事件并返回对应结果。
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
     * @return 处理结果
     */
    private RuntimeEvent event(
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
        return new RuntimeEvent(
            sourceEventId,
            executionKey,
            conversationId,
            runId,
            stepId,
            type,
            status,
            occurredAt,
            truncate(summary, MAX_SUMMARY_LENGTH),
            payload,
            sensitiveLevel,
            queryProjection
        );
    }

    /**
     * 处理{@code type}并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    private RuntimeEventType type(AgentEvent event) {
        return switch (event.getType()) {
            case AGENT_START -> RuntimeEventType.RUN_STARTED;
            case AGENT_END -> RuntimeEventType.RUN_FINISHED;
            case AGENT_RESULT -> RuntimeEventType.RESULT;
            case MODEL_CALL_START -> RuntimeEventType.MODEL_CALL_STARTED;
            case MODEL_CALL_END -> RuntimeEventType.MODEL_CALL_FINISHED;
            case TEXT_BLOCK_START -> RuntimeEventType.TEXT_STARTED;
            case TEXT_BLOCK_DELTA -> RuntimeEventType.TEXT_DELTA;
            case TEXT_BLOCK_END -> RuntimeEventType.TEXT_FINISHED;
            case THINKING_BLOCK_START -> RuntimeEventType.THINKING_STARTED;
            case THINKING_BLOCK_DELTA -> RuntimeEventType.THINKING_DELTA;
            case THINKING_BLOCK_END -> RuntimeEventType.THINKING_FINISHED;
            case DATA_BLOCK_START -> RuntimeEventType.DATA_STARTED;
            case DATA_BLOCK_DELTA -> RuntimeEventType.DATA_DELTA;
            case DATA_BLOCK_END -> RuntimeEventType.DATA_FINISHED;
            case TOOL_CALL_START -> RuntimeEventType.TOOL_CALL_STARTED;
            case TOOL_CALL_DELTA -> RuntimeEventType.TOOL_CALL_DELTA;
            case TOOL_CALL_END -> RuntimeEventType.TOOL_CALL_FINISHED;
            case TOOL_RESULT_START -> RuntimeEventType.TOOL_RESULT_STARTED;
            case TOOL_RESULT_TEXT_DELTA, TOOL_RESULT_DATA_DELTA -> RuntimeEventType.TOOL_RESULT_DELTA;
            case TOOL_RESULT_END -> RuntimeEventType.TOOL_RESULT_FINISHED;
            case REQUIRE_USER_CONFIRM -> RuntimeEventType.APPROVAL_REQUIRED;
            case USER_CONFIRM_RESULT -> RuntimeEventType.APPROVAL_RESOLVED;
            case REQUIRE_EXTERNAL_EXECUTION -> RuntimeEventType.EXTERNAL_EXECUTION_REQUIRED;
            case EXTERNAL_EXECUTION_RESULT -> RuntimeEventType.EXTERNAL_EXECUTION_RESOLVED;
            case SUBAGENT_EXPOSED -> RuntimeEventType.SUBAGENT_EVENT;
            case ALL_TOOLS_DENIED -> RuntimeEventType.PERMISSION_DENIED;
            case EXCEED_MAX_ITERS -> RuntimeEventType.ITERATION_LIMIT_REACHED;
            case REQUEST_STOP -> RuntimeEventType.CANCELLED;
            case HINT_BLOCK, CUSTOM -> RuntimeEventType.CUSTOM;
        };
    }

    /**
     * 处理{@code status}并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    private RuntimeEventStatus status(AgentEvent event) {
        return switch (event.getType()) {
            case REQUIRE_USER_CONFIRM, REQUIRE_EXTERNAL_EXECUTION -> RuntimeEventStatus.PENDING;
            case ALL_TOOLS_DENIED, EXCEED_MAX_ITERS -> RuntimeEventStatus.FAILED;
            default -> RuntimeEventStatus.SUCCESS;
        };
    }

    /**
     * 处理{@code sensitivity}并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    private RuntimeSensitiveLevel sensitivity(AgentEvent event) {
        return switch (event.getType()) {
            case THINKING_BLOCK_START, THINKING_BLOCK_DELTA, THINKING_BLOCK_END ->
                RuntimeSensitiveLevel.SENSITIVE;
            case AGENT_START, AGENT_END, AGENT_RESULT,
                 TEXT_BLOCK_START, TEXT_BLOCK_DELTA, TEXT_BLOCK_END,
                 REQUEST_STOP -> RuntimeSensitiveLevel.PUBLIC;
            default -> RuntimeSensitiveLevel.INTERNAL;
        };
    }

    /**
     * 处理{@code occurredAt}并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    private Instant occurredAt(AgentEvent event) {
        try {
            return Instant.parse(event.getCreatedAt());
        } catch (DateTimeParseException exception) {
            return Instant.now();
        }
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    private String summary(AgentEvent event) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (event instanceof TextBlockDeltaEvent text) {
            return text.getDelta();
        }
        if (event instanceof ThinkingBlockDeltaEvent) {
            return "Agent reasoning delta";
        }
        if (event instanceof ToolCallStartEvent tool) {
            return "Tool call started: " + tool.getToolCallName();
        }
        if (event instanceof RequireUserConfirmEvent confirmation) {
            return "Approval required for " + confirmation.getToolCalls().size() + " tool call(s)";
        }
        if (event instanceof RequireExternalExecutionEvent external) {
            return "External execution required for " + external.getToolCalls().size() + " tool call(s)";
        }
        return event.getType().getValue();
    }

    /**
     * 处理{@code payload}并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    private Map<String, Object> payload(AgentEvent event) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (event.getType() == io.agentscope.core.event.AgentEventType.THINKING_BLOCK_START
            || event.getType() == io.agentscope.core.event.AgentEventType.THINKING_BLOCK_DELTA
            || event.getType() == io.agentscope.core.event.AgentEventType.THINKING_BLOCK_END) {
            Map<String, Object> reasoning = new LinkedHashMap<>();
            reasoning.put("sourceType", event.getType().getValue());
            reasoning.put("sourceEventId", event.getId());
            if (event instanceof ThinkingBlockDeltaEvent delta) {
                reasoning.put("delta", truncate(scrubSecrets(delta.getDelta()), MAX_SUMMARY_LENGTH));
            }
            return sanitizeMap(reasoning);
        }
        Map<String, Object> raw = objectMapper.convertValue(event, new TypeReference<>() {
        });
        Map<String, Object> sanitized = sanitizeMap(raw);
        boolean failureEvent = event.getType() == io.agentscope.core.event.AgentEventType.ALL_TOOLS_DENIED
            || event.getType() == io.agentscope.core.event.AgentEventType.EXCEED_MAX_ITERS;
        boolean retryable = event.getType() == io.agentscope.core.event.AgentEventType.EXCEED_MAX_ITERS;
        try {
            if (objectMapper.writeValueAsBytes(sanitized).length <= MAX_PAYLOAD_BYTES) {
                if (failureEvent) {
                    sanitized.put("retryable", retryable);
                }
                return sanitized;
            }
        } catch (JsonProcessingException exception) {
            // Fall through to a bounded metadata-only payload.
        }
        Map<String, Object> bounded = new LinkedHashMap<>();
        bounded.put("sourceType", event.getType().getValue());
        bounded.put("sourceEventId", event.getId());
        bounded.put("truncated", true);
        if (failureEvent) {
            bounded.put("retryable", retryable);
        }
        return bounded;
    }

    /**
     * 获取{@code Projection}。
     *
     * @param event 事件参数
     * @param context 待处理内容
     * @return 处理结果
     */
    private Map<String, Object> queryProjection(AgentEvent event, ProjectionContext context) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> projection = new LinkedHashMap<>();
        if (event instanceof ModelCallStartEvent modelStart) {
            addModelContext(projection, context);
            putText(projection, "replyId", modelStart.getReplyId());
        } else if (event instanceof ModelCallEndEvent modelEnd) {
            addModelContext(projection, context);
            putText(projection, "replyId", modelEnd.getReplyId());
            if (modelEnd.getUsage() != null) {
                projection.put("promptTokens", modelEnd.getUsage().getInputTokens());
                projection.put("completionTokens", modelEnd.getUsage().getOutputTokens());
                projection.put("cachedTokens", modelEnd.getUsage().getCachedTokens());
                projection.put("totalTokens", modelEnd.getUsage().getTotalTokens());
                projection.put("durationMs", modelEnd.getUsage().getTime() * 1_000D);
            }
        } else if (event instanceof ToolCallStartEvent toolStart) {
            addToolContext(
                projection, context, toolStart.getReplyId(),
                toolStart.getToolCallId(), toolStart.getToolCallName()
            );
        } else if (event instanceof ToolCallDeltaEvent toolDelta) {
            addToolContext(
                projection, context, toolDelta.getReplyId(),
                toolDelta.getToolCallId(), toolDelta.getToolCallName()
            );
            putText(projection, "inputDelta", toolDelta.getDelta());
        } else if (event instanceof ToolCallEndEvent toolEnd) {
            addToolContext(
                projection, context, toolEnd.getReplyId(),
                toolEnd.getToolCallId(), toolEnd.getToolCallName()
            );
        } else if (event instanceof ToolResultStartEvent resultStart) {
            addToolContext(
                projection, context, resultStart.getReplyId(),
                resultStart.getToolCallId(), resultStart.getToolCallName()
            );
        } else if (event instanceof ToolResultTextDeltaEvent resultDelta) {
            addToolContext(
                projection, context, resultDelta.getReplyId(),
                resultDelta.getToolCallId(), resultDelta.getToolCallName()
            );
            putText(projection, "outputDelta", resultDelta.getDelta());
            Object structuredResult = parseStructuredToolResult(resultDelta.getDelta());
            if (structuredResult != null) {
                addSafeToolResultProjection(
                    projection, resultDelta.getToolCallName(), structuredResult
                );
            }
        } else if (event instanceof ToolResultDataDeltaEvent resultData) {
            addToolContext(
                projection, context, resultData.getReplyId(),
                resultData.getToolCallId(), resultData.getToolCallName()
            );
            if (resultData.getData() != null) {
                Object outputData = objectMapper.convertValue(resultData.getData(), Object.class);
                projection.put("outputData", outputData);
                addSafeToolResultProjection(projection, resultData.getToolCallName(), outputData);
            }
        } else if (event instanceof ToolResultEndEvent resultEnd) {
            addToolContext(
                projection, context, resultEnd.getReplyId(),
                resultEnd.getToolCallId(), resultEnd.getToolCallName()
            );
            if (resultEnd.getState() != null) {
                projection.put("toolState", resultEnd.getState().getValue());
            }
        } else if (event instanceof RequireUserConfirmEvent confirmation) {
            addPendingToolProjection(projection, confirmation.getToolCalls(), "permission");
            addBusinessConfirmationRequest(projection, confirmation);
        } else if (event instanceof RequireExternalExecutionEvent external) {
            addPendingToolProjection(projection, external.getToolCalls(), "external");
        }
        return projection.isEmpty() ? Map.of() : boundedProjection(projection);
    }

    /**
     * 创建并保存Pending工具Projection。
     *
     * @param projection {@code projection}参数
     * @param calls {@code calls}参数
     * @param requestType 业务类型
     */
    private void addPendingToolProjection(
        Map<String, Object> projection,
        List<?> calls,
        String requestType
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (calls == null || calls.isEmpty() || calls.size() > 32) {
            return;
        }
        List<Map<String, Object>> safeCalls = new java.util.ArrayList<>();
        for (Object raw : calls) {
            Map<String, Object> converted = objectMapper.convertValue(raw, new TypeReference<>() { });
            Map<String, Object> safe = sanitizeMap(converted);
            if (safe.containsKey("input")) {
                safe.put("input", boundedSafeValue(safe.get("input"), 8 * 1024));
            }
            safeCalls.add(safe);
        }
        projection.put("requestType", requestType);
        projection.put("toolCalls", safeCalls);
        if (safeCalls.size() == 1) {
            projection.put("toolCall", safeCalls.getFirst());
        }
    }

    /**
     * 创建并保存{@code BusinessConfirmationRequest}。
     *
     * @param projection {@code projection}参数
     * @param event 事件参数
     */
    private void addBusinessConfirmationRequest(
        Map<String, Object> projection,
        RequireUserConfirmEvent event
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (event.getReplyId() == null || event.getToolCalls().size() != 1) {
            return;
        }
        io.agentscope.core.message.ToolUseBlock call = event.getToolCalls().getFirst();
        if (!"request_user_confirmation".equals(call.getName())) {
            return;
        }
        Map<String, Object> input = call.getInput();
        Map<String, Object> ui = new LinkedHashMap<>();
        copyText(ui, input, "title", 255);
        copyText(ui, input, "summary", 2000);
        copyText(ui, input, "confirm_label", 64);
        copyText(ui, input, "cancel_label", 64);
        copyText(ui, input, "risk_note", 2000);
        Object rawFields = input.get("fields");
        if (rawFields instanceof List<?> fields) {
            List<Map<String, Object>> safeFields = new java.util.ArrayList<>();
            for (Object raw : fields) {
                if (!(raw instanceof Map<?, ?> field) || safeFields.size() >= 32) {
                    continue;
                }
                Map<String, Object> safe = new LinkedHashMap<>();
                copyText(safe, field, "key", 128);
                copyText(safe, field, "label", 255);
                if (field.containsKey("value")) {
                    safe.put("value", boundedSafeValue(field.get("value"), 4096));
                }
                safe.put("editable", !Boolean.FALSE.equals(field.get("editable")));
                copyText(safe, field, "value_type", 32);
                safeFields.add(safe);
            }
            ui.put("fields", safeFields);
        }
        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("confirmationId", event.getReplyId());
        confirmation.put("status", "awaiting_user");
        confirmation.put("ui", ui);
        projection.put("toolName", "request_user_confirmation");
        projection.put("replyId", event.getReplyId());
        projection.put("confirmationId", event.getReplyId());
        projection.put("confirmationStatus", "awaiting_user");
        projection.put("businessConfirmation", confirmation);
    }

    /**
     * 处理parseStructured工具结果并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Object parseStructuredToolResult(String value) {
        if (value == null || value.isBlank()
            || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_QUERY_PROJECTION_BYTES) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /**
 * 创建并保存Safe工具结果Projection。
 *
     * Exposes only UI-operable metadata from tool results.  The complete result
     * remains behind the ordinary outputData redaction because tool payloads can
     * contain arbitrary business data.
     */
    private void addSafeToolResultProjection(
        Map<String, Object> projection,
        String toolName,
        Object outputData
    ) {
        // 以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。
        String normalized = toolName == null ? "" : toolName.strip().toLowerCase(java.util.Locale.ROOT)
            .replace('-', '_');
        if (normalized.startsWith("builtin.")) {
            normalized = normalized.substring("builtin.".length());
        }
        if (!(outputData instanceof Map<?, ?> envelope)) {
            return;
        }
        Map<?, ?> data = envelope.get("data") instanceof Map<?, ?> nested ? nested : envelope;
        String status = textValue(data.get("status"));
        if (status == null) {
            status = textValue(envelope.get("status"));
        }
        if (status != null) {
            projection.put("resultStatus", truncate(status, 64));
        }
        String message = textValue(data.get("message"));
        if (message == null) {
            message = textValue(envelope.get("error"));
        }
        if (message != null) {
            projection.put("resultMessage", truncate(RuntimeSecretScrubber.scrubText(message), 512));
        }

        addEvidenceProjection(projection, normalized, envelope, status);

        switch (normalized) {
            case "request_user_confirmation" -> addBusinessConfirmation(projection, data);
            case "ask_user_question" -> addUserQuestion(projection, data);
            case "todo_write" -> addTodo(projection, data);
            case "update_dashboard_context" -> addDashboardContext(projection, data);
            case "sub_agent_call" -> {
                putText(projection, "delegationId", textValue(data.get("delegation_id")));
                putText(projection, "delegationStatus", textValue(data.get("status")));
            }
            case "sub_agent_batch_call" -> addBatchDelegationProjection(projection, data);
            case "excel_document_write", "word_document_write" -> addArtifactMetadata(
                projection, data.get("artifact")
            );
            default -> {
                if (normalized.startsWith("search_knowledge_") && "ok".equalsIgnoreCase(status)) {
                    addCitationProjection(projection, data);
                }
            }
        }
    }

    /**
 * 创建并保存{@code EvidenceProjection}。
 *
     * Signs only successful, explicitly enveloped tool results as evidence.
     * Failed/unavailable results remain visible for diagnostics but can never
     * be consumed as grounding evidence by the replay or SSE layers.
     */
    private void addEvidenceProjection(
        Map<String, Object> projection,
        String toolName,
        Map<?, ?> envelope,
        String status
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        boolean explicitFailure = Boolean.FALSE.equals(envelope.get("ok"))
            || status != null && FAILED_RESULT_STATUSES.contains(status);
        if (explicitFailure) {
            projection.put("evidenceStatus", "failed");
            return;
        }
        if ("degraded".equals(status)) {
            projection.put("evidenceStatus", "degraded");
            return;
        }
        if (!Boolean.TRUE.equals(envelope.get("ok"))) {
            return;
        }
        String evidenceType = evidenceType(toolName);
        if (evidenceType != null) {
            projection.put("evidenceType", evidenceType);
            projection.put("evidenceStatus", "verified");
        }
    }

    /**
     * 处理{@code evidenceType}并返回对应结果。
     *
     * @param toolName 名称
     * @return 处理结果
     */
    private String evidenceType(String toolName) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        if (Set.of("search_knowledge_base", "list_accessible_knowledge_bases")
            .contains(toolName)) {
            return "internal_knowledge";
        }
        if (Set.of("get_dataset_schema", "execute_sql_query", "list_accessible_datasets",
            "update_dashboard_context", "sqlite_scratchpad").contains(toolName)) {
            return "internal_data";
        }
        if (Set.of("memory_search", "fetch_user_long_term_memory", "update_user_preference",
            "delete_user_preference").contains(toolName)) {
            return "conversation_memory";
        }
        if (Set.of("read_file", "write_file", "search_text", "directory_tree_navigator",
            "read_image", "excel_document_read", "excel_document_write", "word_document_read",
            "word_document_write").contains(toolName)) {
            return "user_file";
        }
        if (Set.of("web_search_baidu", "web_search_baidu_http", "web_search_bing_http",
            "fetch_static_web_url").contains(toolName)) {
            return "public_web";
        }
        if (toolName.startsWith("browser_") || Set.of(
            "system_http_request", "web_renderer_and_snapshot", "jira_search",
            "jira_create_issue", "jira_get_projects", "send_dingtalk_message", "send_email",
            "send_wechat_work_message", "send_portal_notification"
        ).contains(toolName)) {
            return "external_tool";
        }
        return "runtime_state";
    }

    /**
 * 创建并保存{@code CitationProjection}。
 *
     * Keeps the user-facing knowledge evidence separate from the arbitrary
     * tool result body.  This projection is persisted and can therefore be
     * replayed after a refresh without exposing the full runtime payload.
     */
    private void addCitationProjection(Map<String, Object> projection, Map<?, ?> data) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Object rawCitations = data.get("citations");
        if (!(rawCitations instanceof List<?> citations)) {
            return;
        }
        List<Map<String, Object>> safeCitations = new java.util.ArrayList<>();
        for (Object raw : citations) {
            if (!(raw instanceof Map<?, ?> citation) || safeCitations.size() >= MAX_CITATIONS) {
                continue;
            }
            String id = citationText(citation, MAX_CITATION_TITLE_LENGTH,
                "id", "citationId", "citation_id");
            String content = citationText(citation, MAX_CITATION_CONTENT_LENGTH,
                "content", "text", "snippet", "quote");
            if (id == null || content == null) {
                continue;
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("id", id);
            putCitationText(safe, citation, "chunk_id", MAX_CITATION_TITLE_LENGTH,
                "chunkId", "chunk_id");
            putCitationText(safe, citation, "doc_name", MAX_CITATION_TITLE_LENGTH,
                "documentName", "document_name", "docName", "doc_name");
            putCitationLong(safe, citation, "dataset_id", "knowledgeBaseId", "knowledge_base_id");
            putCitationLong(safe, citation, "doc_id", "documentId", "document_id");
            putCitationLong(safe, citation, "chunk_no", "chunkNo", "chunk_no");
            putCitationNumber(safe, citation, "similarity", "similarity", "score");
            safe.put("content", content);
            safe.put("source_type", "knowledge");
            addCitationMetadata(safe, citation.get("metadata"));
            safeCitations.add(safe);
        }
        if (!safeCitations.isEmpty()) {
            projection.put("citations", safeCitations);
            projection.put("citationCount", safeCitations.size());
        }
    }

    /**
     * 创建并保存Citation元数据。
     *
     * @param target {@code target}参数
     * @param rawMetadata raw元数据参数
     */
    private void addCitationMetadata(Map<String, Object> target, Object rawMetadata) {
        if (!(rawMetadata instanceof Map<?, ?> metadata)) {
            return;
        }
        for (String field : CITATION_METADATA_FIELDS) {
            String value = citationText(metadata, MAX_CITATION_URL_LENGTH, field);
            if (value != null) {
                target.put(field, value);
            }
        }
    }

    /**
     * 处理{@code putCitationText}相关逻辑。
     *
     * @param target {@code target}参数
     * @param source 数据源参数
     * @param targetKey {@code targetKey}参数
     * @param max {@code max}参数
     * @param sourceKeys 数据源Keys参数
     */
    private void putCitationText(
        Map<String, Object> target,
        Map<?, ?> source,
        String targetKey,
        int max,
        String... sourceKeys
    ) {
        String value = citationText(source, max, sourceKeys);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    /**
     * 处理{@code putCitationLong}相关逻辑。
     *
     * @param target {@code target}参数
     * @param source 数据源参数
     * @param targetKey {@code targetKey}参数
     * @param sourceKeys 数据源Keys参数
     */
    private void putCitationLong(
        Map<String, Object> target,
        Map<?, ?> source,
        String targetKey,
        String... sourceKeys
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        for (String key : sourceKeys) {
            Object value = source.get(key);
            if (value instanceof Number number
                && number.longValue() > 0
                && number.doubleValue() == number.longValue()) {
                target.put(targetKey, number.longValue());
                return;
            }
            if (value instanceof String text) {
                try {
                    long parsed = Long.parseLong(text.strip());
                    if (parsed > 0) {
                        target.put(targetKey, parsed);
                        return;
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed identifiers and keep the citation itself.
                }
            }
        }
    }

    /**
     * 处理{@code putCitationNumber}相关逻辑。
     *
     * @param target {@code target}参数
     * @param source 数据源参数
     * @param targetKey {@code targetKey}参数
     * @param sourceKeys 数据源Keys参数
     */
    private void putCitationNumber(
        Map<String, Object> target,
        Map<?, ?> source,
        String targetKey,
        String... sourceKeys
    ) {
        for (String key : sourceKeys) {
            Object value = source.get(key);
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                target.put(targetKey, Math.max(0D, Math.min(1D, number.doubleValue())));
                return;
            }
        }
    }

    /**
     * 处理{@code citationText}并返回对应结果。
     *
     * @param source 数据源参数
     * @param max {@code max}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private String citationText(Map<?, ?> source, int max, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).strip();
            if (!text.isEmpty()) {
                return truncate(RuntimeSecretScrubber.scrubText(text), max);
            }
        }
        return null;
    }

    /**
 * 创建并保存{@code BatchDelegationProjection}。
 * Exposes bounded batch delegation metadata without copying child output into the event. */
    private void addBatchDelegationProjection(Map<String, Object> projection, Map<?, ?> data) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        putText(projection, "delegationStatus", textValue(data.get("status")));
        copyCount(projection, data, "count", "delegationCount");
        copyCount(projection, data, "completed_count", "delegationCompletedCount");
        copyCount(projection, data, "failed_count", "delegationFailedCount");
        copyCount(projection, data, "pending_count", "delegationPendingCount");

        Object rawResults = data.get("results");
        if (!(rawResults instanceof List<?> results)) {
            return;
        }
        List<Map<String, Object>> safeResults = new java.util.ArrayList<>();
        for (Object raw : results) {
            if (!(raw instanceof Map<?, ?> result) || safeResults.size() >= 4) {
                continue;
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            copyText(safe, result, "delegation_id", 128);
            if (!safe.containsKey("delegation_id")) {
                copyText(safe, result, "delegationId", 128);
            }
            copyText(safe, result, "agent_name", 128);
            if (!safe.containsKey("agent_name")) {
                copyText(safe, result, "agentName", 128);
            }
            copyText(safe, result, "status", 64);
            copyText(safe, result, "trace_id", 128);
            if (!safe.containsKey("trace_id")) {
                copyText(safe, result, "traceId", 128);
            }
            copyText(safe, result, "pending_type", 64);
            copyText(safe, result, "error", 512);
            if (!safe.isEmpty()) {
                safeResults.add(safe);
            }
        }
        if (!safeResults.isEmpty()) {
            projection.put("delegationResults", safeResults);
        }
    }

    /**
     * 处理{@code copyCount}相关逻辑。
     *
     * @param target {@code target}参数
     * @param source 数据源参数
     * @param sourceKey 数据源Key参数
     * @param targetKey {@code targetKey}参数
     */
    private void copyCount(Map<String, Object> target, Map<?, ?> source, String sourceKey, String targetKey) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Object value = source.get(sourceKey);
        if (value instanceof Number number) {
            long count = number.longValue();
            if (count >= 0 && count <= 4) {
                target.put(targetKey, count);
            }
            return;
        }
        String text = textValue(value);
        if (text == null) {
            return;
        }
        try {
            long count = Long.parseLong(text);
            if (count >= 0 && count <= 4) {
                target.put(targetKey, count);
            }
        } catch (NumberFormatException ignored) {
            // Ignore malformed counts; status and per-item metadata remain useful.
        }
    }

    /**
 * 创建并保存用户追问。
 * Exposes only the bounded, user-facing portion of an Agent question result. */
    private void addUserQuestion(Map<String, Object> projection, Map<?, ?> data) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<String, Object> question = new LinkedHashMap<>();
        putText(question, "questionId", textValue(data.get("question_id")));
        if (!question.containsKey("questionId")) {
            putText(question, "questionId", textValue(data.get("questionId")));
        }
        copyText(question, data, "question", 2000);
        copyText(question, data, "context", 2000);
        copyText(question, data, "purpose", 255);
        copyText(question, data, "status", 32);
        copyText(question, data, "tool_call_id", 128);
        if (data.get("multi_select") instanceof Boolean value) {
            question.put("multiSelect", value);
        } else if (data.get("multiSelect") instanceof Boolean value) {
            question.put("multiSelect", value);
        }
        if (data.get("allow_custom_input") instanceof Boolean value) {
            question.put("allowCustomInput", value);
        } else if (data.get("allowCustomInput") instanceof Boolean value) {
            question.put("allowCustomInput", value);
        }
        copyText(question, data, "expires_at", 64);
        Object rawOptions = data.get("options");
        if (rawOptions instanceof List<?> options) {
            List<Map<String, Object>> safeOptions = new java.util.ArrayList<>();
            for (Object raw : options) {
                if (!(raw instanceof Map<?, ?> option) || safeOptions.size() >= 12) {
                    continue;
                }
                Map<String, Object> safe = new LinkedHashMap<>();
                copyText(safe, option, "id", 128);
                copyText(safe, option, "label", 500);
                copyText(safe, option, "description", 1000);
                if (safe.containsKey("id") && safe.containsKey("label")) {
                    safeOptions.add(safe);
                }
            }
            if (!safeOptions.isEmpty()) {
                question.put("options", safeOptions);
            }
        }
        if (question.get("questionId") != null && question.get("question") != null
            && question.get("options") != null) {
            projection.put("toolName", "ask_user_question");
            projection.put("questionId", question.get("questionId"));
            projection.put("questionStatus", question.getOrDefault("status", "pending"));
            projection.put("userQuestion", question);
        }
    }

    /**
     * 创建并保存Dashboard上下文。
     *
     * @param projection {@code projection}参数
     * @param data 数据参数
     */
    private void addDashboardContext(Map<String, Object> projection, Map<?, ?> data) {
        Object rawContext = data.get("context");
        if (!(rawContext instanceof Map<?, ?> context)) {
            return;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        copyText(safe, context, "room_name", 255);
        copyText(safe, context, "metric_name", 255);
        copyText(safe, context, "time_range", 128);
        if (!safe.isEmpty()) {
            projection.put("dashboardContext", safe);
        }
    }

    /**
     * 创建并保存{@code BusinessConfirmation}。
     *
     * @param projection {@code projection}参数
     * @param data 数据参数
     */
    private void addBusinessConfirmation(Map<String, Object> projection, Map<?, ?> data) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<String, Object> confirmation = new LinkedHashMap<>();
        putText(confirmation, "confirmationId", textValue(data.get("confirmation_id")));
        putText(confirmation, "status", textValue(data.get("status")));
        Object rawUi = data.get("ui");
        if (rawUi instanceof Map<?, ?> ui) {
            Map<String, Object> safeUi = new LinkedHashMap<>();
            copyText(safeUi, ui, "title", 255);
            copyText(safeUi, ui, "summary", 2000);
            copyText(safeUi, ui, "confirm_label", 64);
            copyText(safeUi, ui, "cancel_label", 64);
            copyText(safeUi, ui, "risk_note", 2000);
            Object rawFields = ui.get("fields");
            if (rawFields instanceof List<?> fields) {
                List<Map<String, Object>> safeFields = new java.util.ArrayList<>();
                for (Object rawField : fields) {
                    if (!(rawField instanceof Map<?, ?> field) || safeFields.size() >= 32) {
                        continue;
                    }
                    Map<String, Object> safeField = new LinkedHashMap<>();
                    copyText(safeField, field, "key", 128);
                    copyText(safeField, field, "label", 255);
                    if (field.containsKey("value")) {
                        safeField.put("value", boundedSafeValue(field.get("value"), 4096));
                    }
                    if (field.containsKey("editable")) {
                        safeField.put("editable", Boolean.TRUE.equals(field.get("editable")));
                    }
                    copyText(safeField, field, "value_type", 32);
                    safeFields.add(safeField);
                }
                safeUi.put("fields", safeFields);
            }
            confirmation.put("ui", safeUi);
        }
        if (!confirmation.isEmpty()) {
            projection.put("businessConfirmation", confirmation);
            putText(projection, "confirmationId", textValue(data.get("confirmation_id")));
            putText(projection, "confirmationStatus", textValue(data.get("status")));
        }
    }

    /**
 * 创建并保存{@code Todo}。
 * Exposes a bounded, UI-only task checklist from the structured tool result. */
    private void addTodo(Map<String, Object> projection, Map<?, ?> data) {
        // 以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。
        Object rawTodos = data.get("todos");
        if (!(rawTodos instanceof List<?> values) || values.size() > 20) {
            return;
        }
        List<Map<String, Object>> todos = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int pending = 0;
        int inProgress = 0;
        int completed = 0;
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> item)) {
                return;
            }
            String content = textValue(item.get("content"));
            String status = textValue(item.get("status"));
            if (content == null || content.length() > 200
                || !java.util.Set.of("pending", "in_progress", "completed").contains(status)
                || !seen.add(content)) {
                return;
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("content", truncate(RuntimeSecretScrubber.scrubText(content), 200));
            safe.put("status", status);
            todos.add(safe);
            switch (status) {
                case "pending" -> pending++;
                case "in_progress" -> inProgress++;
                case "completed" -> completed++;
                default -> throw new IllegalStateException("unreachable todo status");
            }
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("pending", pending);
        counts.put("in_progress", inProgress);
        counts.put("completed", completed);
        Map<String, Object> todo = new LinkedHashMap<>();
        todo.put("type", "todo_update");
        todo.put("todos", todos);
        todo.put("counts", counts);
        projection.put("toolName", "todo_write");
        projection.put("todo", todo);
        projection.put("todoType", "todo_update");
    }

    /**
     * 创建并保存制品元数据。
     *
     * @param projection {@code projection}参数
     * @param rawArtifact raw制品参数
     */
    private void addArtifactMetadata(Map<String, Object> projection, Object rawArtifact) {
        if (!(rawArtifact instanceof Map<?, ?> artifact)) {
            return;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        copyText(safe, artifact, "filename", 255);
        copyText(safe, artifact, "mime_type", 255);
        copyText(safe, artifact, "expires_at", 64);
        if (artifact.get("size") instanceof Number size && size.longValue() >= 0) {
            safe.put("size", size.longValue());
        }
        copyText(safe, artifact, "artifact_id", 128);
        if (!safe.isEmpty()) {
            projection.put("artifact", safe);
        }
    }

    /**
     * 处理{@code copyText}相关逻辑。
     *
     * @param target {@code target}参数
     * @param source 数据源参数
     * @param key {@code key}参数
     * @param max {@code max}参数
     */
    private void copyText(Map<String, Object> target, Map<?, ?> source, String key, int max) {
        String value = textValue(source.get(key));
        if (value != null) {
            target.put(key, truncate(RuntimeSecretScrubber.scrubText(value), max));
        }
    }

    /**
     * 处理{@code boundedSafeValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private Object boundedSafeValue(Object value, int max) {
        Object sanitized = RuntimeSecretScrubber.sanitizeValue("value", value);
        if (sanitized instanceof String text) {
            return truncate(text, max);
        }
        return sanitized;
    }

    /**
     * 处理{@code textValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * 创建并保存模型上下文。
     *
     * @param projection {@code projection}参数
     * @param context 待处理内容
     */
    private void addModelContext(Map<String, Object> projection, ProjectionContext context) {
        putText(projection, "agentName", context.agentName());
        putText(projection, "model", context.model());
        if (context.temperature() != null) {
            projection.put("temperature", context.temperature());
        }
    }

    /**
     * 创建并保存工具上下文。
     *
     * @param projection {@code projection}参数
     * @param context 待处理内容
     * @param replyId 资源标识
     * @param toolCallId 资源标识
     * @param toolName 名称
     */
    private void addToolContext(
        Map<String, Object> projection,
        ProjectionContext context,
        String replyId,
        String toolCallId,
        String toolName
    ) {
        addModelContext(projection, context);
        putText(projection, "replyId", replyId);
        putText(projection, "toolCallId", toolCallId);
        putText(projection, "toolName", toolName);
    }

    /**
     * 处理{@code putText}相关逻辑。
     *
     * @param target {@code target}参数
     * @param key {@code key}参数
     * @param value {@code value}参数
     */
    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * 处理{@code boundedProjection}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> boundedProjection(Map<String, Object> source) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Map<String, Object> sanitized = sanitizeMap(source);
        try {
            if (objectMapper.writeValueAsBytes(sanitized).length <= MAX_QUERY_PROJECTION_BYTES) {
                return sanitized;
            }
        } catch (JsonProcessingException exception) {
            // Fall through to correlation-only metadata.
        }
        Map<String, Object> bounded = new LinkedHashMap<>();
        for (String key : List.of(
            "agentName", "model", "temperature", "replyId", "toolCallId", "toolName",
            "promptTokens", "completionTokens", "cachedTokens", "totalTokens", "durationMs", "toolState",
            "resultStatus", "resultMessage", "businessConfirmation", "confirmationId",
            "confirmationStatus", "delegationId", "delegationStatus", "dashboardContext", "artifact",
            "delegationCount", "delegationCompletedCount", "delegationFailedCount", "delegationPendingCount",
            "delegationResults", "todo", "todoType", "userQuestion", "questionId", "questionStatus",
            "citations", "citationCount", "evidenceType", "evidenceStatus"
        )) {
            if (sanitized.containsKey(key)) {
                bounded.put(key, sanitized.get(key));
            }
        }
        bounded.put("truncated", true);
        return bounded;
    }

    /**
     * 处理{@code sanitizeMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        return RuntimeSecretScrubber.sanitizeMap(source);
    }

    /**
     * 处理safeError消息并返回对应结果。
     *
     * @param throwable {@code throwable}参数
     * @return 处理结果
     */
    private String safeErrorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        String normalized = message == null || message.isBlank() ? "unknown runtime error" : message;
        return truncate(scrubSecrets(normalized), 512);
    }

    /**
     * 处理{@code scrubSecrets}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String scrubSecrets(String value) {
        return RuntimeSecretScrubber.scrubText(value);
    }

    /**
     * 处理{@code truncate}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 封装{@code Projection}相关的不可变数据。
     */
    private record ProjectionContext(String agentName, String model, Number temperature) {

        private static final ProjectionContext EMPTY = new ProjectionContext(null, null, null);

        /**
         * 处理{@code from}并返回对应结果。
         *
         * @param request 请求参数
         * @return 处理结果
         */
        private static ProjectionContext from(AgentRunRequest request) {
            Object rawTemperature = request.model().options().get("temperature");
            Number temperature = rawTemperature instanceof Number number ? number : null;
            return new ProjectionContext(request.agentName(), request.model().modelName(), temperature);
        }

        /**
         * 处理{@code from}并返回对应结果。
         *
         * @param request 请求参数
         * @return 处理结果
         */
        private static ProjectionContext from(AgentResumeRequest request) {
            Object raw = request.decisionMetadata().get("_runtimeContext");
            if (!(raw instanceof Map<?, ?> context)) {
                return EMPTY;
            }
            String agentName = text(context.get("agentName"));
            String model = text(context.get("model"));
            Number temperature = context.get("temperature") instanceof Number number ? number : null;
            return new ProjectionContext(agentName, model, temperature);
        }

        /**
         * 处理{@code text}并返回对应结果。
         *
         * @param value {@code value}参数
         * @return 处理结果
         */
        private static String text(Object value) {
            if (value == null) {
                return null;
            }
            String normalized = String.valueOf(value).strip();
            return normalized.isEmpty() ? null : normalized;
        }
    }
}
