package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 封装知识库Base相关的不可变数据。
 */
public record KnowledgeBaseConfig(
    int chunkSize,
    int chunkOverlap,
    int topK,
    double similarityThreshold,
    double vectorWeight,
    Long embeddingModelId,
    Integer embeddingDimension
) {

    private static final Set<String> KEYS = Set.of(
        "chunkSize", "chunkOverlap", "topK", "similarityThreshold", "vectorWeight",
        "embeddingModelId", "embeddingDimension"
    );

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    public static KnowledgeBaseConfig from(Map<String, Object> source) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> config = source == null ? Map.of() : source;
        if (!KEYS.containsAll(config.keySet())) {
            var unknown = new java.util.TreeSet<>(config.keySet());
            unknown.removeAll(KEYS);
            throw badRequest("知识库配置包含未知字段：" + unknown);
        }
        int chunkSize = integer(config.get("chunkSize"), 1000, 200, 4000, "chunkSize");
        int overlap = integer(config.get("chunkOverlap"), 100, 0, 1000, "chunkOverlap");
        if (overlap >= chunkSize) {
            throw badRequest("chunkOverlap 必须小于 chunkSize");
        }
        int topK = integer(config.get("topK"), 6, 1, 20, "topK");
        double threshold = decimal(
            config.get("similarityThreshold"), 0.2, 0, 1, "similarityThreshold"
        );
        double weight = decimal(config.get("vectorWeight"), 0.7, 0, 1, "vectorWeight");
        Long modelId = positiveLong(config.get("embeddingModelId"), "embeddingModelId");
        Integer dimension = positiveInteger(config.get("embeddingDimension"), "embeddingDimension");
        if ((modelId == null) != (dimension == null)) {
            throw badRequest("embeddingModelId 与 embeddingDimension 必须同时配置");
        }
        if (dimension != null && dimension > 8192) {
            throw badRequest("embeddingDimension 超过 8192 限制");
        }
        return new KnowledgeBaseConfig(
            chunkSize, overlap, topK, threshold, weight, modelId, dimension
        );
    }

    /**
     * 将输入数据转换为{@code Map}。
     *
     * @return 处理结果
     */
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("chunkSize", chunkSize);
        value.put("chunkOverlap", chunkOverlap);
        value.put("topK", topK);
        value.put("similarityThreshold", similarityThreshold);
        value.put("vectorWeight", vectorWeight);
        if (embeddingModelId != null) {
            // Snowflake IDs cross the browser boundary as strings to avoid IEEE-754 truncation.
            value.put("embeddingModelId", embeddingModelId.toString());
            value.put("embeddingDimension", embeddingDimension);
        }
        return Map.copyOf(value);
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private static int integer(
        Object value, int defaultValue, int minimum, int maximum, String label
    ) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
            || number.longValue() < minimum || number.longValue() > maximum) {
            throw badRequest(label + "无效");
        }
        return Math.toIntExact(number.longValue());
    }

    /**
     * 处理{@code decimal}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private static double decimal(
        Object value, double defaultValue, double minimum, double maximum, String label
    ) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
            || number.doubleValue() < minimum || number.doubleValue() > maximum) {
            throw badRequest(label + "无效");
        }
        return number.doubleValue();
    }

    /**
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private static Long positiveLong(Object value, String label) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the same stable validation error used for numeric input.
            }
            throw badRequest(label + "无效");
        }
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
            || number.longValue() <= 0) {
            throw badRequest(label + "无效");
        }
        return number.longValue();
    }

    /**
     * 处理{@code positiveInteger}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private static Integer positiveInteger(Object value, String label) {
        Long number = positiveLong(value, label);
        if (number == null) {
            return null;
        }
        if (number > Integer.MAX_VALUE) {
            throw badRequest(label + "无效");
        }
        return number.intValue();
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private static ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
