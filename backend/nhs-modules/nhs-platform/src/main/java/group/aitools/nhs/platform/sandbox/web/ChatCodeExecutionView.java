package group.aitools.nhs.platform.sandbox.web;

import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;

import java.time.LocalDateTime;

/**
 * 封装对话Code执行相关的不可变数据。
 */
public record ChatCodeExecutionView(
    String executionId,
    Long conversationId,
    String traceId,
    String language,
    String status,
    Integer exitCode,
    long outputBytes,
    boolean truncated,
    String failureCode,
    String failureMessage,
    LocalDateTime queuedAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    public static ChatCodeExecutionView from(SandboxJobRow row) {
        return new ChatCodeExecutionView(
            row.getId().toString(), row.getConversationId(), row.getTraceId(),
            row.getScriptLanguage(), row.getStatus(), row.getExitCode(),
            row.getOutputBytes() == null ? 0 : row.getOutputBytes(),
            Boolean.TRUE.equals(row.getOutputTruncated()), row.getFailureCode(),
            row.getFailureMessage(), row.getCreatedAt(), row.getStartedAt(), row.getFinishedAt()
        );
    }
}
