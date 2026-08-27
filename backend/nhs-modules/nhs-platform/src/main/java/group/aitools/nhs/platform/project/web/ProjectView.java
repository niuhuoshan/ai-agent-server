package group.aitools.nhs.platform.project.web;

import group.aitools.nhs.platform.project.domain.AgentProject;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装项目相关的不可变数据。
 * Authorized project projection. */
public record ProjectView(
    Long id,
    String projectKey,
    String name,
    String description,
    String status,
    Long ownerId,
    Long defaultAgentVersionId,
    Map<String, Object> workspacePolicy,
    Map<String, Object> notificationPolicy,
    List<String> tags,
    LocalDateTime archivedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param project 项目参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static ProjectView from(AgentProject project, JsonMapper jsonMapper) {
        return new ProjectView(
            project.getId(), project.getProjectKey(), project.getName(), project.getDescription(),
            project.getStatus(), project.getOwnerId(), project.getDefaultAgentVersionId(),
            map(project.getWorkspacePolicyJson(), jsonMapper),
            map(project.getNotificationPolicyJson(), jsonMapper),
            list(project.getTagsJson(), jsonMapper), project.getArchivedAt(),
            project.getCreateTime(), project.getUpdateTime()
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

    /**
     * 查询{@code list}列表。
     *
     * @param json {@code json}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 符合条件的数据集合
     */
    private static List<String> list(String json, JsonMapper jsonMapper) {
        return json == null ? List.of() : jsonMapper.readValue(json, STRING_LIST);
    }
}
