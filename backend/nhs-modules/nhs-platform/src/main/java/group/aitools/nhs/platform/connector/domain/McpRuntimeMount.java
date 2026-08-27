package group.aitools.nhs.platform.connector.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Mcp运行时Mount相关的领域对象。
 * Durable identity and lifecycle counters for a session/run MCP mount. */
@Data
public class McpRuntimeMount {

    private Long id;
    private Long connectorId;
    private Long connectorRevision;
    private String scopeType;
    private String scopeKey;
    private Long userId;
    private Long conversationId;
    private Long taskId;
    private Long runId;
    private Long stepId;
    private String sessionId;
    private String executionId;
    private String traceId;
    private String status;
    private Integer connectionAttempts;
    private Integer reconnectCount;
    private Long invocationCount;
    private Long failureCount;
    private LocalDateTime openedAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime closedAt;
    private String lastErrorSummary;
}
