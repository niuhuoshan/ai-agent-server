package group.aitools.nhs.platform.conversation.web;

/**
 * 封装会话Finalize相关的不可变数据。
 * Durable result of an explicit Nhs conversation finalization request. */
public record ConversationFinalizeResult(
    boolean finalized,
    Long conversationId,
    String reason
) {
}
