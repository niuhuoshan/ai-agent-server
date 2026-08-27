package group.aitools.nhs.platform.agent.web;

import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装智能体版本相关的不可变数据。
 * Complete Agent version including immutable resource snapshots. */
public record AgentVersionView(
    Long id,
    Long agentId,
    int versionNo,
    String systemPrompt,
    Long modelId,
    Long synthesisModelId,
    Map<String, Object> runtimeConfig,
    Map<String, Object> welcomeConfig,
    List<String> routingTags,
    String status,
    String contentHash,
    LocalDateTime publishedAt,
    Long createdBy,
    LocalDateTime createdAt,
    List<AgentVersionBindingView> bindings
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param version 版本参数
     * @param bindings {@code bindings}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static AgentVersionView from(
        AgentDefinitionVersion version,
        List<AgentVersionBindingRow> bindings,
        JsonMapper jsonMapper
    ) {
        return new AgentVersionView(
            version.getId(),
            version.getAgentId(),
            version.getVersionNo(),
            version.getSystemPrompt(),
            version.getModelId(),
            version.getSynthesisModelId(),
            parseMap(version.getRuntimeConfigJson(), jsonMapper),
            parseMap(version.getWelcomeConfigJson(), jsonMapper),
            version.getRoutingTagsJson() == null
                ? List.of() : jsonMapper.readValue(version.getRoutingTagsJson(), STRING_LIST_TYPE),
            version.getStatus(),
            version.getContentHash(),
            version.getPublishedAt(),
            version.getCreatedBy(),
            version.getCreatedAt(),
            bindings.stream().map(row -> AgentVersionBindingView.from(row, jsonMapper)).toList()
        );
    }

    /**
     * 处理{@code parseMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    private static Map<String, Object> parseMap(String value, JsonMapper jsonMapper) {
        return value == null ? Map.of() : jsonMapper.readValue(value, MAP_TYPE);
    }
}
