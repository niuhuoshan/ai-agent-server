package group.aitools.nhs.platform.model.web;

import group.aitools.nhs.platform.model.domain.AgentModel;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装模型相关的不可变数据。
 * Secret-free model projection returned by management APIs. */
public record ModelView(
    Long id,
    String modelKey,
    String displayName,
    String providerType,
    String modelName,
    String modelType,
    String endpointUrl,
    boolean apiKeyConfigured,
    Integer contextSize,
    Integer maxOutputTokens,
    Map<String, Object> reasoningConfig,
    String status,
    Map<String, Object> capabilities,
    LocalDateTime createTime,
    LocalDateTime updateTime
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param model 模型参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static ModelView from(AgentModel model, JsonMapper jsonMapper) {
        return new ModelView(
            model.getId(),
            model.getModelKey(),
            model.getDisplayName(),
            model.getProviderType(),
            model.getModelName(),
            model.getModelType(),
            model.getEndpointUrl(),
            hasStoredApiKey(model.getCredentialRef()),
            model.getContextSize(),
            model.getMaxOutputTokens(),
            parseMap(model.getReasoningConfigJson(), jsonMapper),
            model.getStatus(),
            parseMap(model.getCapabilityJson(), jsonMapper),
            model.getCreateTime(),
            model.getUpdateTime()
        );
    }

    /**
     * 处理{@code parseMap}并返回对应结果。
     *
     * @param json {@code json}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    private static Map<String, Object> parseMap(String json, JsonMapper jsonMapper) {
        return json == null || json.isBlank() ? Map.of() : jsonMapper.readValue(json, MAP_TYPE);
    }

    /**
     * 判断Stored接口Key是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean hasStoredApiKey(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.strip();
        return !normalized.startsWith("v1s.") && !normalized.startsWith("env:");
    }
}
