package group.aitools.nhs.runtime.spi;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * 封装智能体Resume相关的不可变数据。
 * Durable human decision used to resume a paused AgentScope execution. */
public record AgentResumeRequest(
    RuntimeExecutionKey executionKey,
    Long userId,
    Long conversationId,
    Long taskId,
    Long runId,
    Long stepId,
    String sessionId,
    String replyId,
    RuntimeResumeDecision decision,
    List<Map<String, Object>> pendingActions,
    Map<String, Object> decisionMetadata,
    RuntimeResumeMode mode
) {

    /**
 * 创建 {@code AgentResumeRequest} 实例并初始化所需依赖。
 * Backward-compatible approval constructor for provider integrations. */
    public AgentResumeRequest(
        RuntimeExecutionKey executionKey,
        Long userId,
        Long conversationId,
        Long taskId,
        Long runId,
        Long stepId,
        String sessionId,
        String replyId,
        RuntimeResumeDecision decision,
        Map<String, Object> pendingAction,
        Map<String, Object> decisionMetadata
    ) {
        this(
            executionKey,
            userId,
            conversationId,
            taskId,
            runId,
            stepId,
            sessionId,
            replyId,
            decision,
            pendingAction == null ? List.of() : List.of(pendingAction),
            decisionMetadata,
            RuntimeResumeMode.APPROVAL
        );
    }

    /**
 * 创建 {@code AgentResumeRequest} 实例并初始化所需依赖。
 * Convenience constructor for a single action or a manual continuation. */
    public AgentResumeRequest(
        RuntimeExecutionKey executionKey,
        Long userId,
        Long conversationId,
        Long taskId,
        Long runId,
        Long stepId,
        String sessionId,
        String replyId,
        RuntimeResumeDecision decision,
        Map<String, Object> pendingAction,
        Map<String, Object> decisionMetadata,
        RuntimeResumeMode mode
    ) {
        this(
            executionKey,
            userId,
            conversationId,
            taskId,
            runId,
            stepId,
            sessionId,
            replyId,
            decision,
            pendingAction == null || pendingAction.isEmpty() ? List.of() : List.of(pendingAction),
            decisionMetadata,
            mode
        );
    }

    /**
     * 创建 {@code AgentResumeRequest} 实例并初始化所需依赖。
     *
     * @param executionKey 执行Key参数
     * @param userId 资源标识
     * @param conversationId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param sessionId 资源标识
     * @param replyId 资源标识
     * @param decision {@code decision}参数
     * @param pendingActions {@code pendingActions}参数
     * @param decisionMetadata decision元数据参数
     * @param mode {@code mode}参数
     */
    public AgentResumeRequest {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (executionKey == null) {
            throw new IllegalArgumentException("executionKey must not be null");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (conversationId == null && runId == null) {
            throw new IllegalArgumentException("conversationId or runId is required");
        }
        requirePositiveIfPresent(conversationId, "conversationId");
        requirePositiveIfPresent(taskId, "taskId");
        requirePositiveIfPresent(runId, "runId");
        requirePositiveIfPresent(stepId, "stepId");
        sessionId = requireText(sessionId, "sessionId");
        replyId = requireText(replyId, "replyId");
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        pendingActions = immutableMaps(pendingActions);
        if ((mode == RuntimeResumeMode.APPROVAL || mode == RuntimeResumeMode.EXTERNAL_EXECUTION)
            && pendingActions.isEmpty()) {
            throw new IllegalArgumentException("pendingActions must not be empty");
        }
        decisionMetadata = immutableMap(decisionMetadata);
    }

    /**
 * 处理{@code pendingAction}并返回对应结果。
 * Compatibility accessor for integrations that only support one action. */
    public Map<String, Object> pendingAction() {
        return pendingActions.isEmpty() ? Map.of() : pendingActions.getFirst();
    }

    /**
 * 处理with运行时上下文并返回对应结果。
 *
     * Carries the non-secret runtime identity needed when a resume provider
     * cannot expose its frozen request back to the event mapper.  The values
     * are generated from the already-authorized snapshot; callers must not
     * populate this from untrusted request data.
     */
    public AgentResumeRequest withRuntimeContext(AgentRunRequest frozen) {
        if (frozen == null) {
            return this;
        }
        if (!executionKey.equals(frozen.executionKey())) {
            throw new IllegalArgumentException("frozen runtime execution key does not match resume request");
        }
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("agentName", frozen.agentName());
        runtime.put("model", frozen.model().modelName());
        Object rawTemperature = frozen.model().options().get("temperature");
        if (rawTemperature instanceof Number number) {
            runtime.put("temperature", number);
        }
        Map<String, Object> metadata = new LinkedHashMap<>(decisionMetadata);
        metadata.put("_runtimeContext", runtime);
        return new AgentResumeRequest(
            executionKey, userId, conversationId, taskId, runId, stepId,
            sessionId, replyId, decision, pendingActions, metadata, mode
        );
    }

    /**
     * 校验{@code Text}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param name 名称
     * @return 处理结果
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    /**
     * 处理{@code immutableMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 处理{@code immutableMaps}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 符合条件的数据集合
     */
    private static List<Map<String, Object>> immutableMaps(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            Map<String, Object> immutable = immutableMap(item);
            if (immutable.isEmpty()) {
                throw new IllegalArgumentException("pendingActions must not contain an empty action");
            }
            result.add(immutable);
        }
        return List.copyOf(result);
    }

    /**
     * 校验{@code PositiveIfPresent}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param name 名称
     */
    private static void requirePositiveIfPresent(Long value, String name) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
