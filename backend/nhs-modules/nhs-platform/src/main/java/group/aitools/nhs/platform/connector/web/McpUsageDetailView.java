package group.aitools.nhs.platform.connector.web;

import group.aitools.nhs.platform.connector.domain.McpUsageDetail;

import java.time.LocalDateTime;

/**
 * 封装{@code McpUsageDetail}相关的不可变数据。
 * Secret-free service usage row for one MCP tool call. */
public record McpUsageDetailView(
    Long id,
    Long mountId,
    Long connectorId,
    Long connectorRevision,
    Long toolId,
    String externalToolName,
    Long userId,
    Long conversationId,
    Long taskId,
    Long runId,
    Long stepId,
    String sessionId,
    String executionId,
    String traceId,
    String status,
    int attemptCount,
    long latencyMs,
    long requestBytes,
    Long responseBytes,
    String errorSummary,
    LocalDateTime startedAt,
    LocalDateTime completedAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static McpUsageDetailView from(McpUsageDetail value) {
        return new McpUsageDetailView(
            value.getId(), value.getMountId(), value.getConnectorId(), value.getConnectorRevision(),
            value.getToolId(), value.getExternalToolName(), value.getUserId(),
            value.getConversationId(), value.getTaskId(), value.getRunId(), value.getStepId(),
            value.getSessionId(), value.getExecutionId(), value.getTraceId(), value.getStatus(),
            value.getAttemptCount() == null ? 0 : value.getAttemptCount(),
            value.getLatencyMs() == null ? 0 : value.getLatencyMs(),
            value.getRequestBytes() == null ? 0 : value.getRequestBytes(), value.getResponseBytes(),
            value.getErrorSummary(), value.getStartedAt(), value.getCompletedAt()
        );
    }
}
