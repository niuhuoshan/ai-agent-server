package group.aitools.nhs.platform.connector.web;

import group.aitools.nhs.platform.connector.domain.McpRuntimeHealth;

import java.time.LocalDateTime;

/**
 * 封装Mcp运行时健康状态相关的不可变数据。
 * Operator-facing MCP health and circuit-breaker snapshot. */
public record McpRuntimeHealthView(
    Long connectorId,
    String healthStatus,
    String circuitState,
    int consecutiveFailures,
    long totalConnections,
    long totalReconnections,
    long totalInvocations,
    long totalSuccesses,
    long totalFailures,
    long activeMountCount,
    LocalDateTime lastSuccessAt,
    LocalDateTime lastFailureAt,
    LocalDateTime lastReconnectAt,
    LocalDateTime circuitOpenUntil,
    Long lastLatencyMs,
    String lastErrorSummary,
    LocalDateTime updatedAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static McpRuntimeHealthView from(Long connectorId, McpRuntimeHealth value) {
        if (value == null) {
            return new McpRuntimeHealthView(
                connectorId, "unknown", "closed", 0, 0, 0, 0, 0, 0, 0,
                null, null, null, null, null, null, null
            );
        }
        return new McpRuntimeHealthView(
            value.getConnectorId(), value.getHealthStatus(), value.getCircuitState(),
            integer(value.getConsecutiveFailures()), number(value.getTotalConnections()),
            number(value.getTotalReconnections()), number(value.getTotalInvocations()),
            number(value.getTotalSuccesses()), number(value.getTotalFailures()),
            number(value.getActiveMountCount()), value.getLastSuccessAt(), value.getLastFailureAt(),
            value.getLastReconnectAt(), value.getCircuitOpenUntil(), value.getLastLatencyMs(),
            value.getLastErrorSummary(), value.getUpdatedAt()
        );
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static int integer(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static long number(Long value) {
        return value == null ? 0 : value;
    }
}
