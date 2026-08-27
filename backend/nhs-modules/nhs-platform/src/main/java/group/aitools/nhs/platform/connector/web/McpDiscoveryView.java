package group.aitools.nhs.platform.connector.web;

import group.aitools.nhs.platform.connector.domain.AgentMcpDiscovery;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装{@code McpDiscovery}相关的不可变数据。
 */
public record McpDiscoveryView(
    Long id,
    Long connectorId,
    Long connectorRevision,
    String status,
    String protocolVersion,
    Map<String, Object> serverInfo,
    Integer toolCount,
    String contentHash,
    String errorSummary,
    Long startedBy,
    LocalDateTime startedAt,
    LocalDateTime completedAt
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static McpDiscoveryView from(AgentMcpDiscovery value, JsonMapper jsonMapper) {
        Map<String, Object> serverInfo = value.getServerInfoJson() == null
            ? Map.of() : jsonMapper.readValue(value.getServerInfoJson(), MAP_TYPE);
        return new McpDiscoveryView(
            value.getId(), value.getConnectorId(), value.getConnectorRevision(), value.getStatus(),
            value.getProtocolVersion(), serverInfo, value.getToolCount(), value.getContentHash(),
            value.getErrorSummary(), value.getStartedBy(), value.getStartedAt(), value.getCompletedAt()
        );
    }
}
