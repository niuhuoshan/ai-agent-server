package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 表示工具Argument相关的领域对象。
 * Applies bounded structural and basic JSON Schema validation before remote execution. */
@Component
public class ToolArgumentValidator {

    private static final int MAX_ARGUMENT_BYTES = 64 * 1024;
    private static final int MAX_RESULT_BYTES = 1024 * 1024;
    private static final int MAX_DEPTH = 20;

    private final JsonMapper jsonMapper;

    public ToolArgumentValidator(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 校验{@code validate}，并在条件不满足时终止处理。
     *
     * @param arguments {@code arguments}参数
     * @param schema {@code schema}参数
     * @return 处理结果
     */
    public String validate(Map<String, Object> arguments, Map<String, Object> schema) {
        if (arguments == null) {
            throw badRequest("工具参数不能为空");
        }
        String json = jsonMapper.writeValueAsString(arguments);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
            throw badRequest("工具参数超过 64KB 限制");
        }
        validateJsonValue(arguments, 0);
        validateSchema(arguments, schema, "$", 0);
        return json;
    }

    /**
     * 处理bounded结果Json并返回对应结果。
     *
     * @param result 结果参数
     * @return 处理结果
     */
    public String boundedResultJson(Object result) {
        String json = jsonMapper.writeValueAsString(result);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw new ServiceException("工具结果超过 1MB 限制", 502);
        }
        return json;
    }

    /**
     * 校验{@code JsonValue}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     */
    private void validateJsonValue(Object value, int depth) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (depth > MAX_DEPTH) {
            throw badRequest("工具参数嵌套层级过深");
        }
        if (value == null || value instanceof String || value instanceof Number
            || value instanceof Boolean) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 512) {
                throw badRequest("工具参数对象字段过多");
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank() || key.length() > 128) {
                    throw badRequest("工具参数包含无效字段名");
                }
                validateJsonValue(entry.getValue(), depth + 1);
            }
            return;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 2048) {
                throw badRequest("工具参数数组元素过多");
            }
            list.forEach(item -> validateJsonValue(item, depth + 1));
            return;
        }
        throw badRequest("工具参数包含不支持的值类型");
    }

    /**
     * 校验{@code Schema}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param schema {@code schema}参数
     * @param path {@code path}参数
     * @param depth {@code depth}参数
     */
    private void validateSchema(Object value, Map<String, Object> schema, String path, int depth) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (schema == null || schema.isEmpty() || depth > MAX_DEPTH) {
            return;
        }
        Object expectedType = schema.get("type");
        if (expectedType instanceof String type && !matchesType(value, type)) {
            throw badRequest("工具参数类型不匹配：" + path);
        }
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value)) {
            throw badRequest("工具参数不在允许值范围：" + path);
        }
        if (value instanceof Map<?, ?> object) {
            if (schema.get("required") instanceof List<?> required) {
                for (Object field : required) {
                    if (field instanceof String name && !object.containsKey(name)) {
                        throw badRequest("工具参数缺少必填字段：" + path + "." + name);
                    }
                }
            }
            Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> map
                ? map : Map.of();
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                for (Object key : object.keySet()) {
                    if (!properties.containsKey(key)) {
                        throw badRequest("工具参数包含未声明字段：" + path + "." + key);
                    }
                }
            }
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                Object childSchema = properties.get(entry.getKey());
                if (childSchema instanceof Map<?, ?> raw) {
                    validateSchema(entry.getValue(), stringMap(raw), path + "." + entry.getKey(), depth + 1);
                }
            }
        } else if (value instanceof List<?> list && schema.get("items") instanceof Map<?, ?> raw) {
            Map<String, Object> itemSchema = stringMap(raw);
            for (int index = 0; index < list.size(); index++) {
                validateSchema(list.get(index), itemSchema, path + "[" + index + "]", depth + 1);
            }
        }
    }

    /**
     * 判断{@code Type}是否满足要求。
     *
     * @param value {@code value}参数
     * @param type 业务类型
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean matchesType(Object value, String type) {
        return switch (type) {
            case "null" -> value == null;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number number
                && Double.isFinite(number.doubleValue())
                && number.doubleValue() == number.longValue();
            case "boolean" -> value instanceof Boolean;
            default -> true;
        };
    }

    /**
     * 处理{@code stringMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
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
