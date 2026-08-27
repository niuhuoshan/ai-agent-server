package group.aitools.nhs.platform.agent.web;

import group.aitools.nhs.platform.agent.domain.AgentDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装智能体相关的不可变数据。
 * Agent identity and current published-version summary. */
public record AgentView(
    Long id,
    String agentKey,
    String name,
    String description,
    String agentType,
    String engineType,
    String avatarUrl,
    boolean systemAgent,
    boolean defaultAgent,
    String status,
    Long ownerId,
    int sortOrder,
    Map<String, Object> engineConfig,
    Long publishedVersionId,
    LocalDateTime createTime,
    LocalDateTime updateTime
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param definition 定义参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static AgentView from(AgentDefinition definition, JsonMapper jsonMapper) {
        Map<String, Object> engineConfig = definition.getEngineConfigJson() == null
            ? Map.of() : jsonMapper.readValue(definition.getEngineConfigJson(), MAP_TYPE);
        return new AgentView(
            definition.getId(),
            definition.getAgentKey(),
            definition.getName(),
            definition.getDescription(),
            definition.getAgentType(),
            definition.getEngineType(),
            definition.getAvatarUrl(),
            Boolean.TRUE.equals(definition.getIsSystem()),
            Boolean.TRUE.equals(definition.getIsDefault()),
            definition.getStatus(),
            definition.getOwnerId(),
            definition.getSortOrder(),
            engineConfig,
            definition.getPublishedVersionId(),
            definition.getCreateTime(),
            definition.getUpdateTime()
        );
    }
}
