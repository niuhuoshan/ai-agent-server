package group.aitools.nhs.platform.task.service;

import group.aitools.nhs.platform.common.ContentHashing;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 表示任务版本ContentHasher相关的领域对象。
 * Canonical hash for immutable task-version content, independent of JSON object key order. */
@Component
public class TaskVersionContentHasher {

    private final JsonMapper jsonMapper;

    public TaskVersionContentHasher(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 判断{@code h}是否满足要求。
     *
     * @param title {@code title}参数
     * @param objective {@code objective}参数
     * @param contextJson 待处理内容
     * @param resourceJson 资源Json参数
     * @param acceptanceJson 验收Json参数
     * @param inputJson {@code inputJson}参数
     * @return 处理结果
     */
    public String hash(
        String title,
        String objective,
        String contextJson,
        String resourceJson,
        String acceptanceJson,
        String inputJson
    ) {
        Map<String, Object> content = new TreeMap<>();
        content.put("title", title);
        content.put("objective", objective);
        content.put("context", canonicalize(parseDocument(contextJson)));
        content.put("resources", canonicalize(parseDocument(resourceJson)));
        content.put("acceptance", canonicalize(parseDocument(acceptanceJson)));
        content.put("input", canonicalize(parseDocument(inputJson)));
        return ContentHashing.sha256(jsonMapper.writeValueAsString(content));
    }

    /**
     * 处理parse文档并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Object parseDocument(String value) {
        return value == null || value.isBlank() ? Map.of() : jsonMapper.readValue(value, Object.class);
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
}
