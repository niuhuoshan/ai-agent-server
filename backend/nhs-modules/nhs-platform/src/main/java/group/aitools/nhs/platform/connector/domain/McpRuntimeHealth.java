package group.aitools.nhs.platform.connector.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Mcp运行时健康状态相关的领域对象。
 * Persisted health and circuit-breaker snapshot for one MCP connector. */
@Data
public class McpRuntimeHealth {

    private Long connectorId;
    private String healthStatus;
    private String circuitState;
    private Integer consecutiveFailures;
    private Long totalConnections;
    private Long totalReconnections;
    private Long totalInvocations;
    private Long totalSuccesses;
    private Long totalFailures;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastFailureAt;
    private LocalDateTime lastReconnectAt;
    private LocalDateTime circuitOpenUntil;
    private Long lastLatencyMs;
    private String lastErrorSummary;
    private LocalDateTime updatedAt;
    private Long revisionNo;
    private Long activeMountCount;
}
