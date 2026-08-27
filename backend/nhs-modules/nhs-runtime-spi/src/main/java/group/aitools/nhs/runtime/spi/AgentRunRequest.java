package group.aitools.nhs.runtime.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/**
 * 封装智能体Run相关的不可变数据。
 * Frozen input required to start one agent turn or formal task step. */
public record AgentRunRequest(
    RuntimeExecutionKey executionKey,
    Long userId,
    Long conversationId,
    Long taskId,
    Long runId,
    Long stepId,
    Long agentVersionId,
    String agentName,
    String sessionId,
    String input,
    String systemPrompt,
    RuntimeModelConfig model,
    String workspaceKey,
    int maxIterations,
    Map<String, Object> authorizationSnapshot,
    Map<String, Object> attributes
) {

    /**
     * 创建 {@code AgentRunRequest} 实例并初始化所需依赖。
     *
     * @param executionKey 执行Key参数
     * @param userId 资源标识
     * @param conversationId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param agentVersionId 资源标识
     * @param agentName 名称
     * @param sessionId 资源标识
     * @param input {@code input}参数
     * @param systemPrompt 系统提示词参数
     * @param model 模型参数
     * @param workspaceKey 工作空间Key参数
     * @param maxIterations {@code maxIterations}参数
     * @param authorizationSnapshot 授权快照参数
     * @param attributes {@code attributes}参数
     */
    public AgentRunRequest {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (executionKey == null) {
            throw new IllegalArgumentException("executionKey must not be null");
        }
        requirePositive(userId, "userId");
        requirePositive(agentVersionId, "agentVersionId");
        if (conversationId == null && runId == null) {
            throw new IllegalArgumentException("conversationId or runId is required");
        }
        requirePositiveIfPresent(conversationId, "conversationId");
        requirePositiveIfPresent(taskId, "taskId");
        requirePositiveIfPresent(runId, "runId");
        requirePositiveIfPresent(stepId, "stepId");
        agentName = requireText(agentName, "agentName");
        sessionId = requireText(sessionId, "sessionId");
        input = requireText(input, "input");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        workspaceKey = workspaceKey == null || workspaceKey.isBlank() ? null : workspaceKey.strip();
        if (maxIterations < 1 || maxIterations > 100) {
            throw new IllegalArgumentException("maxIterations must be between 1 and 100");
        }
        authorizationSnapshot = immutableMap(authorizationSnapshot);
        attributes = immutableMap(attributes);
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
     * 校验{@code Positive}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param name 名称
     */
    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
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

    /**
     * 处理{@code immutableMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
