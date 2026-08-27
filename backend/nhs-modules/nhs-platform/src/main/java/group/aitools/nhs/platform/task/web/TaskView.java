package group.aitools.nhs.platform.task.web;

import group.aitools.nhs.platform.task.domain.AgentTask;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装任务相关的不可变数据。
 * Task projection shared by the list and detail APIs. */
public record TaskView(
    Long id,
    String taskKey,
    Long projectId,
    String title,
    String objective,
    String background,
    Long sourceConversationId,
    Map<String, Object> contextSnapshot,
    String visibility,
    String category,
    String orchestrationMode,
    String lifecycleLevel,
    String riskLevel,
    String status,
    Integer importance,
    Integer urgency,
    Long ownerId,
    String ownerPrincipalType,
    LocalDateTime startAt,
    Long currentVersionId,
    Long latestRunId,
    String acceptanceMode,
    Map<String, Object> acceptanceConfig,
    Map<String, Object> budget,
    Map<String, Object> externalRefs,
    List<String> tags,
    LocalDateTime createdAt
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param task 任务参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static TaskView from(AgentTask task, JsonMapper jsonMapper) {
        Map<String, Object> context = task.getContextSnapshotJson() == null
            ? Map.of()
            : jsonMapper.readValue(task.getContextSnapshotJson(), MAP_TYPE);
        return new TaskView(
            task.getId(),
            task.getTaskKey(),
            task.getProjectId(),
            task.getTitle(),
            task.getObjective(),
            task.getBackground(),
            task.getSourceConversationId(),
            context,
            task.getVisibility(),
            task.getCategory(),
            task.getOrchestrationMode(),
            task.getLifecycleLevel(),
            task.getRiskLevel(),
            task.getStatus(),
            task.getImportance(),
            task.getUrgency(),
            task.getOwnerId(),
            task.getOwnerPrincipalType(),
            task.getStartAt(),
            task.getCurrentVersionId(),
            task.getLatestRunId(),
            task.getAcceptanceMode(),
            map(task.getAcceptanceConfigJson(), jsonMapper),
            map(task.getBudgetJson(), jsonMapper),
            map(task.getExternalRefsJson(), jsonMapper),
            task.getTagsJson() == null ? List.of() : jsonMapper.readValue(task.getTagsJson(), STRING_LIST),
            task.getCreateTime()
        );
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param json {@code json}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    private static Map<String, Object> map(String json, JsonMapper jsonMapper) {
        return json == null ? Map.of() : jsonMapper.readValue(json, MAP_TYPE);
    }
}
