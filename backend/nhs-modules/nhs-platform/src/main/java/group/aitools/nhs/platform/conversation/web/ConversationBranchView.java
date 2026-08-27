package group.aitools.nhs.platform.conversation.web;

/**
 * 封装会话Branch相关的不可变数据。
 * Result of regenerating a user message on a newly-created conversation branch. */
public record ConversationBranchView(
    ConversationView conversation,
    ConversationTurnView turn,
    Long forkMessageId,
    Integer contextCutoffSequence,
    boolean replayed
) {
}
