package group.aitools.nhs.platform.connector.web;

import group.aitools.nhs.platform.connector.domain.McpRuntimeMount;

import java.time.LocalDateTime;

/**
 * 封装Mcp运行时Mount相关的不可变数据。
 * One persisted MCP mount lifecycle, without its internal deduplication key. */
public record McpRuntimeMountView(
    Long id,
    Long connectorId,
    Long connectorRevision,
    String scopeType,
    Long userId,
    Long conversationId,
    Long taskId,
    Long runId,
    Long stepId,
    String sessionId,
    String executionId,
    String traceId,
    String status,
    int connectionAttempts,
    int reconnectCount,
    long invocationCount,
    long failureCount,
    LocalDateTime openedAt,
    LocalDateTime lastUsedAt,
    LocalDateTime closedAt,
    String lastErrorSummary
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static McpRuntimeMountView from(McpRuntimeMount value) {
        return new McpRuntimeMountView(
            value.getId(), value.getConnectorId(), value.getConnectorRevision(),
            value.getScopeType(), value.getUserId(), value.getConversationId(), value.getTaskId(),
            value.getRunId(), value.getStepId(), value.getSessionId(), value.getExecutionId(),
            value.getTraceId(), value.getStatus(), integer(value.getConnectionAttempts()),
            integer(value.getReconnectCount()), number(value.getInvocationCount()),
            number(value.getFailureCount()), value.getOpenedAt(), value.getLastUsedAt(),
            value.getClosedAt(), value.getLastErrorSummary()
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
