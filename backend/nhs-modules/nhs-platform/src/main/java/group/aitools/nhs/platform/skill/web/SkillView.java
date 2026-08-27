package group.aitools.nhs.platform.skill.web;

import group.aitools.nhs.platform.skill.domain.AgentSkill;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装技能相关的不可变数据。
 */
public record SkillView(
    Long id,
    String skillKey,
    String name,
    String description,
    String scopeType,
    Long scopeId,
    Long ownerId,
    String status,
    Long revision,
    Long publishedVersionId,
    Integer publishedVersionNo,
    String publishedContentHash,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    Map<String, Object> metadata
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static SkillView from(AgentSkill value, JsonMapper jsonMapper) {
        return new SkillView(
            value.getId(), value.getSkillKey(), value.getName(), value.getDescription(),
            value.getScopeType(), value.getScopeId(), value.getOwnerId(), value.getStatus(),
            value.getRevisionNo(), value.getPublishedVersionId(), value.getPublishedVersionNo(),
            value.getPublishedContentHash(), value.getCreateTime(), value.getUpdateTime(),
            map(value.getExtraJson(), jsonMapper)
        );
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param value {@code value}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    private static Map<String, Object> map(String value, JsonMapper jsonMapper) {
        return value == null ? Map.of() : jsonMapper.readValue(value, MAP_TYPE);
    }
}
