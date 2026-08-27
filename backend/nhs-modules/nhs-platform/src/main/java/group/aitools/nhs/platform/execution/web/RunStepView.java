package group.aitools.nhs.platform.execution.web;

import group.aitools.nhs.platform.execution.domain.AgentRunStep;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装{@code RunStep}相关的不可变数据。
 * Public workflow progress without frozen authorization, model credentials or system prompts. */
public record RunStepView(
    Long id,
    Long runId,
    String key,
    String type,
    String role,
    int sequence,
    String status,
    Long agentVersionId,
    List<String> dependsOn,
    String inputSummary,
    String outputSummary,
    Object output,
    String waitReason,
    String errorCode,
    String errorSummary,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    int retryCount
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param step {@code step}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static RunStepView from(AgentRunStep step, JsonMapper jsonMapper) {
        return new RunStepView(
            step.getId(), step.getRunId(), step.getStepKey(), step.getStepType(),
            step.getRoleKey(), step.getSequenceNo(), step.getStatus(), step.getAgentVersionId(),
            parseDependencies(step.getDependsOnJson(), jsonMapper), step.getInputSummary(),
            step.getOutputSummary(), parseOutput(step.getOutputJson(), jsonMapper),
            step.getWaitReason(), step.getErrorCode(), step.getErrorSummary(),
            step.getStartedAt(), step.getFinishedAt(),
            step.getRetryCount() == null ? 0 : step.getRetryCount()
        );
    }

    /**
     * 处理{@code parseDependencies}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 符合条件的数据集合
     */
    private static List<String> parseDependencies(String value, JsonMapper jsonMapper) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = jsonMapper.readValue(value, Object.class);
            if (parsed instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
        } catch (RuntimeException ignored) {
            // Invalid durable workflow state is represented without leaking raw data.
        }
        return List.of();
    }

    /**
     * 处理{@code parseOutput}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    private static Object parseOutput(String value, JsonMapper jsonMapper) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(value, Object.class);
        } catch (RuntimeException ignored) {
            return Map.of("invalid", true);
        }
    }
}
