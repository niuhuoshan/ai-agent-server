package group.aitools.nhs.platform.conversation.web;

/**
 * 封装会话Cancellation相关的不可变数据。
 * Durable/global cancellation facts exposed by the Nhs compatibility API. */
public record ConversationCancellationResult(
    Long conversationId,
    String traceId,
    boolean success,
    boolean laneReleased,
    int sessionLocksReleased,
    boolean runCancelled,
    int canvasStopped,
    int taskRunsCancelled,
    String status,
    String reason,
    Long turnId
) {
}
