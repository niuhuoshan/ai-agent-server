package group.aitools.nhs.platform.skill.web;

import group.aitools.nhs.platform.skill.domain.AgentSkillVersion;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装技能版本相关的不可变数据。
 */
public record SkillVersionView(
    Long id,
    Long skillId,
    Integer versionNo,
    String content,
    String contentHash,
    Map<String, Object> manifest,
    Map<String, Object> runtimeRequirements,
    String status,
    LocalDateTime publishedAt,
    Long createdBy,
    LocalDateTime createdAt
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
    public static SkillVersionView from(AgentSkillVersion value, JsonMapper jsonMapper) {
        return new SkillVersionView(
            value.getId(), value.getSkillId(), value.getVersionNo(), value.getContent(),
            value.getContentHash(), map(value.getManifestJson(), jsonMapper),
            map(value.getRuntimeRequirementsJson(), jsonMapper), value.getStatus(),
            value.getPublishedAt(), value.getCreatedBy(), value.getCreatedAt()
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
