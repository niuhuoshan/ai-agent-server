package group.aitools.nhs.platform.task.web;

import group.aitools.nhs.platform.task.domain.AgentTaskResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装任务资源相关的不可变数据。
 * Current task resource binding projection. */
public record TaskResourceView(
    Long id,
    Long taskId,
    String resourceType,
    Long resourceId,
    String permission,
    boolean required,
    String grantSource,
    Map<String, Object> grantSnapshot,
    LocalDateTime createdAt
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param resource 资源参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static TaskResourceView from(AgentTaskResource resource, JsonMapper jsonMapper) {
        Map<String, Object> snapshot = resource.getGrantSnapshotJson() == null
            ? Map.of() : jsonMapper.readValue(resource.getGrantSnapshotJson(), MAP_TYPE);
        return new TaskResourceView(
            resource.getId(), resource.getTaskId(), resource.getResourceType(),
            resource.getResourceId(), resource.getPermission(), Boolean.TRUE.equals(resource.getRequired()),
            resource.getGrantSource(), snapshot, resource.getCreatedAt()
        );
    }
}
