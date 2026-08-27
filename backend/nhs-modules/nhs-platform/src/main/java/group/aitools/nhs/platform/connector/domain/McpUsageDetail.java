package group.aitools.nhs.platform.connector.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示{@code McpUsageDetail}相关的领域对象。
 * Secret-free MCP invocation measurements retained for operations and billing. */
@Data
public class McpUsageDetail {

    private Long id;
    private Long mountId;
    private Long connectorId;
    private Long connectorRevision;
    private Long toolId;
    private String externalToolName;
    private Long userId;
    private Long conversationId;
    private Long taskId;
    private Long runId;
    private Long stepId;
    private String sessionId;
    private String executionId;
    private String traceId;
    private String status;
    private Integer attemptCount;
    private Long latencyMs;
    private Long requestBytes;
    private Long responseBytes;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
