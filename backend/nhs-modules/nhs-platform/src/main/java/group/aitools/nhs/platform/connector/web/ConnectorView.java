package group.aitools.nhs.platform.connector.web;

import group.aitools.nhs.platform.connector.domain.AgentConnector;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装连接器相关的不可变数据。
 */
public record ConnectorView(
    Long id,
    String connectorKey,
    String name,
    String providerType,
    String scope,
    Long ownerId,
    boolean ownedByCurrentUser,
    boolean manageable,
    String endpointUrl,
    String credentialRef,
    Map<String, Object> config,
    String status,
    LocalDateTime lastCheckAt,
    String lastError,
    Long revision,
    Long lastDiscoveryId,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param currentPrincipalId 资源标识
     * @param manageable {@code manageable}参数
     * @return 处理结果
     */
    public static ConnectorView from(
        AgentConnector value,
        JsonMapper jsonMapper,
        Long currentPrincipalId,
        boolean manageable
    ) {
        Map<String, Object> config = value.getConfigJson() == null
            ? Map.of() : jsonMapper.readValue(value.getConfigJson(), MAP_TYPE);
        return new ConnectorView(
            value.getId(), value.getConnectorKey(), value.getName(), value.getProviderType(),
            value.getScopeType(), value.getOwnerId(),
            value.getOwnerId() != null && value.getOwnerId().equals(currentPrincipalId), manageable,
            value.getEndpointUrl(), value.getCredentialRef(), config, value.getStatus(),
            value.getLastCheckAt(), value.getLastError(), value.getRevisionNo(),
            value.getLastDiscoveryId(), value.getCreateTime(), value.getUpdateTime()
        );
    }
}
