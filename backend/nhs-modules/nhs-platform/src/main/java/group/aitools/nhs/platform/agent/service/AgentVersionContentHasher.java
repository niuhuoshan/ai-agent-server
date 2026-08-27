package group.aitools.nhs.platform.agent.service;

import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.common.ContentHashing;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 表示智能体版本ContentHasher相关的领域对象。
 * Stable hash over all executable Agent version content and resource snapshots. */
@Component
public class AgentVersionContentHasher {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code AgentVersionContentHasher} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     */
    public AgentVersionContentHasher(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 判断{@code h}是否满足要求。
     *
     * @param version 版本参数
     * @param bindings {@code bindings}参数
     * @return 处理结果
     */
    public String hash(AgentDefinitionVersion version, List<AgentVersionBindingRow> bindings) {
        Map<String, Object> content = new TreeMap<>();
        content.put("systemPrompt", version.getSystemPrompt());
        content.put("modelId", version.getModelId());
        content.put("synthesisModelId", version.getSynthesisModelId());
        content.put("runtimeConfig", canonicalize(parseMap(version.getRuntimeConfigJson())));
        content.put("welcomeConfig", canonicalize(parseMap(version.getWelcomeConfigJson())));
        content.put("routingTags", canonicalize(parseList(version.getRoutingTagsJson())));
        content.put("bindings", bindings.stream()
            .sorted(Comparator.comparing(AgentVersionBindingRow::getResourceType)
                .thenComparing(AgentVersionBindingRow::getResourceId))
            .map(this::bindingContent)
            .toList());
        return ContentHashing.sha256(jsonMapper.writeValueAsString(content));
    }

    /**
     * 处理{@code bindingContent}并返回对应结果。
     *
     * @param binding {@code binding}参数
     * @return 处理结果
     */
    private Map<String, Object> bindingContent(AgentVersionBindingRow binding) {
        Map<String, Object> content = new TreeMap<>();
        content.put("resourceType", binding.getResourceType());
        content.put("resourceId", binding.getResourceId());
        content.put("permission", binding.getPermission());
        content.put("config", canonicalize(parseMap(binding.getConfigJson())));
        return content;
    }

    /**
     * 判断{@code onicalize}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            list.forEach(item -> normalized.add(canonicalize(item)));
            return normalized;
        }
        return value;
    }

    /**
     * 处理{@code parseMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> parseMap(String value) {
        return value == null || value.isBlank() ? Map.of() : jsonMapper.readValue(value, MAP_TYPE);
    }

    /**
     * 处理{@code parseList}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<String> parseList(String value) {
        return value == null || value.isBlank() ? List.of() : jsonMapper.readValue(value, LIST_TYPE);
    }
}
