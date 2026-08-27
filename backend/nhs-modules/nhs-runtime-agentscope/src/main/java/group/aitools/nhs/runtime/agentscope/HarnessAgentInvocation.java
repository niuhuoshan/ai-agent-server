package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.runtime.spi.RuntimeResumeMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 表示Harness智能体调用相关的领域对象。
 * Thin lifecycle wrapper around an AgentScope HarnessAgent. */
public final class HarnessAgentInvocation implements AgentScopeInvocation {

    private final HarnessAgent agent;
    private final ObjectMapper objectMapper;
    private final AgentRunRequest frozenRunRequest;
    private final Runnable closeAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    public HarnessAgentInvocation(HarnessAgent agent, ObjectMapper objectMapper) {
        this(agent, objectMapper, null);
    }

    /**
     * 创建 {@code HarnessAgentInvocation} 实例并初始化所需依赖。
     *
     * @param agent 智能体参数
     * @param objectMapper {@code objectMapper}参数
     * @param frozenRunRequest 请求参数
     */
    public HarnessAgentInvocation(
        HarnessAgent agent,
        ObjectMapper objectMapper,
        AgentRunRequest frozenRunRequest
    ) {
        this(agent, objectMapper, frozenRunRequest, () -> { });
    }

    /**
     * 创建 {@code HarnessAgentInvocation} 实例并初始化所需依赖。
     *
     * @param agent 智能体参数
     * @param objectMapper {@code objectMapper}参数
     * @param frozenRunRequest 请求参数
     * @param closeAction {@code closeAction}参数
     */
    public HarnessAgentInvocation(
        HarnessAgent agent,
        ObjectMapper objectMapper,
        AgentRunRequest frozenRunRequest,
        Runnable closeAction
    ) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.frozenRunRequest = frozenRunRequest;
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction must not be null");
    }

    /**
     * 处理{@code stream}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Override
    public Flux<AgentEvent> stream(AgentRunRequest request) {
        return agent.streamEvents(userMessage(request), runtimeContext(request));
    }

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Override
    public Flux<AgentEvent> resume(AgentResumeRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (request.mode() == RuntimeResumeMode.CONTINUE) {
            UserMessage continueMessage = UserMessage.builder()
                .textContent("Continue the interrupted task from the persisted session state.")
                .build();
            RuntimeContext.Builder context = RuntimeContext.builder()
                .userId(request.userId().toString())
                .sessionId(request.sessionId())
                .put("executionId", request.executionKey().executionId())
                .put("traceId", request.executionKey().traceId())
                .put("resumeMode", RuntimeResumeMode.CONTINUE.name().toLowerCase(Locale.ROOT));
            putIfPresent(context, "conversationId", request.conversationId());
            putIfPresent(context, "taskId", request.taskId());
            putIfPresent(context, "runId", request.runId());
            putIfPresent(context, "stepId", request.stepId());
            return agent.streamEvents(continueMessage, context.build());
        }
        if (request.mode() == RuntimeResumeMode.EXTERNAL_EXECUTION) {
            List<ToolResultBlock> results = request.pendingActions().stream()
                .map(this::externalToolResult)
                .toList();
            ToolResultMessage resultMessage = new ToolResultMessage(results);
            RuntimeContext.Builder context = RuntimeContext.builder()
                .userId(request.userId().toString())
                .sessionId(request.sessionId())
                .put("executionId", request.executionKey().executionId())
                .put("traceId", request.executionKey().traceId())
                .put("resumeMode", "external_execution")
                .put("replyId", request.replyId())
                .put("decisionMetadata", request.decisionMetadata());
            putIfPresent(context, "conversationId", request.conversationId());
            putIfPresent(context, "taskId", request.taskId());
            putIfPresent(context, "runId", request.runId());
            putIfPresent(context, "stepId", request.stepId());
            return agent.streamEvents(resultMessage, context.build());
        }
        List<ConfirmResult> results = request.pendingActions().stream()
            .map(this::pendingTool)
            .map(tool -> new ConfirmResult(
                request.decision() == RuntimeResumeDecision.APPROVE,
                tool
            ))
            .toList();
        UserMessage decisionMessage = UserMessage.builder()
            .textContent(request.decision() == RuntimeResumeDecision.APPROVE ? "Approved" : "Rejected")
            .metadata(Map.of(
                Msg.METADATA_CONFIRM_RESULTS, results,
                Msg.METADATA_CONFIRM_REQUEST_REPLY_ID, request.replyId()
            ))
            .build();
        RuntimeContext.Builder context = RuntimeContext.builder()
            .userId(request.userId().toString())
            .sessionId(request.sessionId())
            .put("executionId", request.executionKey().executionId())
            .put("traceId", request.executionKey().traceId())
            .put("decisionMetadata", request.decisionMetadata());
        putIfPresent(context, "conversationId", request.conversationId());
        putIfPresent(context, "taskId", request.taskId());
        putIfPresent(context, "runId", request.runId());
        putIfPresent(context, "stepId", request.stepId());
        return agent.streamEvents(decisionMessage, context.build());
    }

    /**
     * 处理{@code frozenRunRequest}并返回对应结果。
     *
     * @return 处理结果
     */
    @Override
    public AgentRunRequest frozenRunRequest() {
        return frozenRunRequest;
    }

    /**
     * 处理{@code interrupt}相关逻辑。
     *
     * @param reason {@code reason}参数
     */
    @Override
    public void interrupt(String reason) {
        agent.interrupt(new UserMessage(reason));
    }

    /**
     * 处理{@code close}相关逻辑。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            agent.close();
        } finally {
            closeAction.run();
        }
    }

    /**
     * 执行time上下文相关的处理流程。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private RuntimeContext runtimeContext(AgentRunRequest request) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
            .userId(request.userId().toString())
            .sessionId(request.sessionId())
            .put("executionId", request.executionKey().executionId())
            .put("traceId", request.executionKey().traceId())
            .put("agentVersionId", request.agentVersionId())
            .put("authorizationSnapshot", request.authorizationSnapshot());
        request.attributes().forEach((key, value) -> {
            if (!"embedMedia".equals(key)) {
                builder.put(key, value);
            }
        });
        putIfPresent(builder, "conversationId", request.conversationId());
        putIfPresent(builder, "taskId", request.taskId());
        putIfPresent(builder, "runId", request.runId());
        putIfPresent(builder, "stepId", request.stepId());
        return builder.build();
    }

    /**
     * 处理用户消息并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private UserMessage userMessage(AgentRunRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.builder().text(request.input()).build());
        Object raw = request.attributes().get("embedMedia");
        if (raw == null) {
            return new UserMessage(blocks);
        }
        if (!(raw instanceof List<?> media) || media.size() > 5) {
            throw new IllegalArgumentException("embedMedia must contain at most five images");
        }
        for (Object value : media) {
            if (!(value instanceof Map<?, ?> source)) {
                throw new IllegalArgumentException("embedMedia item must be an object");
            }
            String mimeType = textValue(source.get("mimeType"));
            String base64 = textValue(source.get("base64"));
            if (!Set.of("image/png", "image/jpeg", "image/webp").contains(mimeType)
                || base64 == null || base64.length() > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("embedMedia image is invalid");
            }
            try {
                Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("embedMedia image is not valid base64", exception);
            }
            blocks.add(new ImageBlock(new Base64Source(mimeType, base64)));
        }
        return new UserMessage(blocks);
    }

    /**
     * 处理{@code textValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String textValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    /**
     * 处理{@code putIfPresent}相关逻辑。
     *
     * @param builder {@code builder}参数
     * @param key {@code key}参数
     * @param value {@code value}参数
     */
    private void putIfPresent(RuntimeContext.Builder builder, String key, Long value) {
        if (value != null) {
            builder.put(key, value);
        }
    }

    /**
     * 处理pending工具并返回对应结果。
     *
     * @param snapshot 快照参数
     * @return 处理结果
     */
    private ToolUseBlock pendingTool(Map<String, Object> snapshot) {
        return new ToolUseBlock(
            requiredText(snapshot, "id"),
            requiredText(snapshot, "name"),
            mapValue(snapshot, "input"),
            optionalText(snapshot.get("content")),
            mapValue(snapshot, "metadata"),
            toolCallState(snapshot.get("state"))
        );
    }

    /**
     * 处理external工具结果并返回对应结果。
     *
     * @param snapshot 快照参数
     * @return 处理结果
     */
    private ToolResultBlock externalToolResult(Map<String, Object> snapshot) {
        String id = requiredText(snapshot, "id");
        String name = requiredText(snapshot, "name");
        Object result = snapshot.get("result");
        if (result == null) {
            throw new IllegalArgumentException("pendingAction.result must not be null");
        }
        boolean succeeded = Boolean.TRUE.equals(snapshot.get("succeeded"));
        String text;
        try {
            text = objectMapper.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("pendingAction.result must be JSON serializable", exception);
        }
        return new ToolResultBlock(
            id,
            name,
            List.of(TextBlock.builder().text(text).build()),
            Map.of("externalExecution", true),
            succeeded ? ToolResultState.SUCCESS : ToolResultState.ERROR
        );
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param source 数据源参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private String requiredText(Map<String, Object> source, String key) {
        String value = optionalText(source.get(key));
        if (value == null) {
            throw new IllegalArgumentException("pendingAction." + key + " must not be blank");
        }
        return value;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).strip();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 将输入数据转换为{@code Value}。
     *
     * @param source 数据源参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private Map<String, Object> mapValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> sourceMap)) {
            throw new IllegalArgumentException("pendingAction." + key + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        sourceMap.forEach((entryKey, entryValue) -> {
            if (!(entryKey instanceof String textKey) || textKey.isBlank()) {
                throw new IllegalArgumentException(
                    "pendingAction." + key + " contains an invalid key"
                );
            }
            result.put(textKey, entryValue);
        });
        return result;
    }

    /**
     * 将输入数据转换为{@code olCallState}。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private ToolCallState toolCallState(Object value) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (value == null) {
            return ToolCallState.PENDING;
        }
        if (value instanceof ToolCallState state) {
            return state;
        }
        String normalized = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
        for (ToolCallState state : ToolCallState.values()) {
            if (state.getValue().equals(normalized)
                || state.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return state;
            }
        }
        throw new IllegalArgumentException("pendingAction.state is invalid: " + value);
    }

    /**
     * 处理harness智能体并返回对应结果。
     *
     * @return 处理结果
     */
    HarnessAgent harnessAgent() {
        return agent;
    }
}
