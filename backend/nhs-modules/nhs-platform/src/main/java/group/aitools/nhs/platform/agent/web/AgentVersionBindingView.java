package group.aitools.nhs.platform.agent.web;

import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 封装智能体版本Binding相关的不可变数据。
 * Frozen resource snapshot attached to an Agent version. */
public record AgentVersionBindingView(
    Long id,
    String resourceType,
    Long resourceId,
    String permission,
    Map<String, Object> config
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param row {@code row}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static AgentVersionBindingView from(AgentVersionBindingRow row, JsonMapper jsonMapper) {
        Map<String, Object> config = row.getConfigJson() == null
            ? Map.of() : jsonMapper.readValue(row.getConfigJson(), MAP_TYPE);
        return new AgentVersionBindingView(
            row.getId(), row.getResourceType(), row.getResourceId(), row.getPermission(), config
        );
    }
}
