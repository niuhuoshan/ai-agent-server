package group.aitools.nhs.platform.model.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表示模型配置相关的领域对象。
 * Fail-closed validator for the options copied into runtime model snapshots. */
@Component
public class ModelConfigurationValidator {

    private static final Set<String> REASONING_KEYS = Set.of(
        "temperature", "topP", "frequencyPenalty", "presencePenalty",
        "thinkingBudget", "reasoningEffort", "parallelToolCalls",
        "nativeStructuredOutput", "nativeStructuredOutputWithTools", "endpointPath"
    );
    private static final Set<String> CAPABILITY_KEYS = Set.of(
        "streaming", "toolCalling", "vision", "jsonSchema", "reasoning",
        "inputModalities", "outputModalities"
    );
    private static final Set<String> BOOLEAN_CAPABILITY_KEYS = Set.of(
        "streaming", "toolCalling", "vision", "jsonSchema", "reasoning"
    );

    /**
     * 处理{@code reasoning}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    public Map<String, Object> reasoning(Map<String, Object> source) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> input = source == null ? Map.of() : source;
        rejectUnknown(input, REASONING_KEYS, "推理配置");
        Map<String, Object> result = new LinkedHashMap<>();
        putNumber(input, result, "temperature", 0, 2);
        putNumber(input, result, "topP", 0, 1);
        putNumber(input, result, "frequencyPenalty", -2, 2);
        putNumber(input, result, "presencePenalty", -2, 2);
        putInteger(input, result, "thinkingBudget", 0, 1_000_000);
        putBoolean(input, result, "parallelToolCalls");
        putBoolean(input, result, "nativeStructuredOutput");
        putBoolean(input, result, "nativeStructuredOutputWithTools");

        Object effort = input.get("reasoningEffort");
        if (effort != null) {
            if (!(effort instanceof String text) || !Set.of("low", "medium", "high").contains(text)) {
                throw badRequest("reasoningEffort 仅支持 low、medium、high");
            }
            result.put("reasoningEffort", effort);
        }
        Object endpointPath = input.get("endpointPath");
        if (endpointPath != null) {
            if (!(endpointPath instanceof String text) || text.isBlank() || text.length() > 255
                || !text.startsWith("/") || text.contains("..") || text.contains("?")
                || text.contains("#") || text.indexOf('\\') >= 0) {
                throw badRequest("endpointPath 必须是安全的绝对路径");
            }
            result.put("endpointPath", text);
        }
        return Map.copyOf(result);
    }

    /**
     * 处理{@code capabilities}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    public Map<String, Object> capabilities(Map<String, Object> source) {
        Map<String, Object> input = source == null ? Map.of() : source;
        rejectUnknown(input, CAPABILITY_KEYS, "能力配置");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : BOOLEAN_CAPABILITY_KEYS) {
            putBoolean(input, result, key);
        }
        putTextList(input, result, "inputModalities");
        putTextList(input, result, "outputModalities");
        return Map.copyOf(result);
    }

    /**
     * 处理{@code rejectUnknown}相关逻辑。
     *
     * @param input {@code input}参数
     * @param supported {@code supported}参数
     * @param label {@code label}参数
     */
    private void rejectUnknown(Map<String, Object> input, Set<String> supported, String label) {
        List<String> unknown = input.keySet().stream().filter(key -> !supported.contains(key)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw badRequest(label + "包含不支持的字段：" + unknown);
        }
    }

    /**
     * 处理{@code putNumber}相关逻辑。
     *
     * @param input {@code input}参数
     * @param output {@code output}参数
     * @param key {@code key}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     */
    private void putNumber(
        Map<String, Object> input,
        Map<String, Object> output,
        String key,
        double minimum,
        double maximum
    ) {
        Object raw = input.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number number)) {
            throw badRequest(key + " 必须是数值");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw badRequest(key + " 超出允许范围");
        }
        output.put(key, value);
    }

    /**
     * 处理{@code putInteger}相关逻辑。
     *
     * @param input {@code input}参数
     * @param output {@code output}参数
     * @param key {@code key}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     */
    private void putInteger(
        Map<String, Object> input,
        Map<String, Object> output,
        String key,
        int minimum,
        int maximum
    ) {
        Object raw = input.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number number)) {
            throw badRequest(key + " 必须是整数");
        }
        long value = number.longValue();
        if (number.doubleValue() != value || value < minimum || value > maximum) {
            throw badRequest(key + " 超出允许的整数范围");
        }
        output.put(key, Math.toIntExact(value));
    }

    /**
     * 处理{@code putBoolean}相关逻辑。
     *
     * @param input {@code input}参数
     * @param output {@code output}参数
     * @param key {@code key}参数
     */
    private void putBoolean(Map<String, Object> input, Map<String, Object> output, String key) {
        Object raw = input.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Boolean)) {
            throw badRequest(key + " 必须是布尔值");
        }
        output.put(key, raw);
    }

    /**
     * 处理{@code putTextList}相关逻辑。
     *
     * @param input {@code input}参数
     * @param output {@code output}参数
     * @param key {@code key}参数
     */
    private void putTextList(Map<String, Object> input, Map<String, Object> output, String key) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Object raw = input.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> values) || values.size() > 16) {
            throw badRequest(key + " 必须是最多 16 项的文本数组");
        }
        List<String> normalized = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank() || text.length() > 32) {
                throw badRequest(key + " 包含无效值");
            }
            normalized.add(text.strip().toLowerCase());
        }
        output.put(key, List.copyOf(normalized));
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
