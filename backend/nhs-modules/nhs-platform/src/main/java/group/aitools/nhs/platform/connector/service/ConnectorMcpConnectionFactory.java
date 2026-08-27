package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.platform.connector.domain.AgentConnector;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

/**
 * 负责连接器McpConnection相关的转换、解析或处理逻辑。
 * Reconstructs an MCP connection from a persisted connector without exposing its secret. */
@org.springframework.stereotype.Component
public class ConnectorMcpConnectionFactory {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private final ConnectorEndpointPolicy endpointPolicy;
    private final ConnectorConfigurationValidator validator;
    private final ConnectorCredentialResolver credentialResolver;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ConnectorMcpConnectionFactory} 实例并初始化所需依赖。
     *
     * @param endpointPolicy endpoint策略参数
     * @param validator {@code validator}参数
     * @param credentialResolver 凭据Resolver参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ConnectorMcpConnectionFactory(
        ConnectorEndpointPolicy endpointPolicy,
        ConnectorConfigurationValidator validator,
        ConnectorCredentialResolver credentialResolver,
        JsonMapper jsonMapper
    ) {
        this.endpointPolicy = endpointPolicy;
        this.validator = validator;
        this.credentialResolver = credentialResolver;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param connector 连接器参数
     * @return 处理结果
     */
    public McpRemoteClient.Connection create(AgentConnector connector) {
        if (connector == null || !"mcp".equals(connector.getProviderType())) {
            throw new IllegalArgumentException("MCP 连接器配置无效");
        }
        Map<String, Object> raw;
        try {
            raw = jsonMapper.readValue(connector.getConfigJson(), MAP_TYPE);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("MCP 连接器配置无效");
        }
        Map<String, Object> config = validator.config("mcp", raw);
        String credential = credentialResolver.resolve(connector.getCredentialRef());
        return new McpRemoteClient.Connection(
            endpointPolicy.normalize(connector.getEndpointUrl()),
            String.valueOf(config.get("transport")), String.valueOf(config.get("authType")),
            config.get("authHeader") instanceof String header ? header : null,
            credential,
            Duration.ofMillis(((Number) config.get("connectTimeoutMs")).longValue()),
            Duration.ofMillis(((Number) config.get("requestTimeoutMs")).longValue())
        );
    }
}
