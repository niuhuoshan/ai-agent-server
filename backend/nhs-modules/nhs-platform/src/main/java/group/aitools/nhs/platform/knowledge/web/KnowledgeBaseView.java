package group.aitools.nhs.platform.knowledge.web;

import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装知识库Base相关的不可变数据。
 */
public record KnowledgeBaseView(
    Long id,
    String knowledgeKey,
    String name,
    String description,
    String providerType,
    String visibility,
    String status,
    Map<String, Object> config,
    Long ownerId,
    Long revision,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param source 数据源参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    public static KnowledgeBaseView from(AgentKnowledgeBase source, JsonMapper mapper) {
        Map<String, Object> config = mapper.readValue(source.getConfigJson(), MAP_TYPE);
        return new KnowledgeBaseView(
            source.getId(), source.getKnowledgeKey(), source.getName(), source.getDescription(),
            source.getProviderType(), source.getVisibility(), source.getStatus(),
            config == null ? Map.of() : config, source.getOwnerId(), source.getRevisionNo(),
            source.getCreateTime(), source.getUpdateTime()
        );
    }
}
