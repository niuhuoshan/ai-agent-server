package group.aitools.nhs.platform.search.service;

import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.service.ConnectorConfigurationValidator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

/**
 * 封装Search提供方相关的不可变数据。
 * Validated immutable configuration used for one search-provider call. */
public record SearchProviderConfig(
    String engine,
    String requestMethod,
    String queryParam,
    String countParam,
    int maxResults,
    int rateLimitPerMinute,
    int failureThreshold,
    int cooldownSeconds,
    String authType,
    String authHeader,
    Duration connectTimeout,
    Duration requestTimeout
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param connector 连接器参数
     * @param validator {@code validator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static SearchProviderConfig from(
        AgentConnector connector,
        ConnectorConfigurationValidator validator,
        JsonMapper jsonMapper
    ) {
        if (connector == null || !"search".equals(connector.getProviderType())) {
            throw new IllegalArgumentException("搜索连接器配置无效");
        }
        Map<String, Object> raw = jsonMapper.readValue(connector.getConfigJson(), MAP_TYPE);
        Map<String, Object> value = validator.config("search", raw);
        return new SearchProviderConfig(
            String.valueOf(value.get("engine")),
            String.valueOf(value.get("requestMethod")),
            String.valueOf(value.get("queryParam")),
            String.valueOf(value.get("countParam")),
            number(value, "maxResults"),
            number(value, "rateLimitPerMinute"),
            number(value, "failureThreshold"),
            number(value, "cooldownSeconds"),
            String.valueOf(value.get("authType")),
            value.get("authHeader") instanceof String header ? header : null,
            Duration.ofMillis(number(value, "connectTimeoutMs")),
            Duration.ofMillis(number(value, "requestTimeoutMs"))
        );
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private static int number(Map<String, Object> value, String key) {
        return ((Number) value.get(key)).intValue();
    }
}
