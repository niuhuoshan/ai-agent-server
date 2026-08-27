package group.aitools.nhs.platform.task.web;

import group.aitools.nhs.platform.task.domain.AgentTaskVersion;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装任务版本相关的不可变数据。
 * Immutable task version projection used for audit and run preparation. */
public record TaskVersionView(
    Long id,
    Long taskId,
    Integer versionNo,
    String title,
    String objective,
    Long agentVersionId,
    Long workflowVersionId,
    Map<String, Object> contextSnapshot,
    Map<String, Object> resourceSnapshot,
    Map<String, Object> acceptanceSnapshot,
    Map<String, Object> inputSnapshot,
    String contentHash,
    Long createdBy,
    LocalDateTime createdAt
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param version 版本参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static TaskVersionView from(AgentTaskVersion version, JsonMapper jsonMapper) {
        return new TaskVersionView(
            version.getId(), version.getTaskId(), version.getVersionNo(), version.getTitle(),
            version.getObjective(), version.getAgentVersionId(), version.getWorkflowVersionId(),
            map(version.getContextSnapshotJson(), jsonMapper),
            map(version.getResourceSnapshotJson(), jsonMapper),
            map(version.getAcceptanceSnapshotJson(), jsonMapper),
            map(version.getInputSnapshotJson(), jsonMapper), version.getContentHash(),
            version.getCreatedBy(), version.getCreatedAt()
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
