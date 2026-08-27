package group.aitools.nhs.platform.task.web;

/**
 * 封装任务Draft相关的不可变数据。
 * Editable task preview; creating this value never creates a formal task. */
public record TaskDraftView(
    Long conversationId,
    String draftHash,
    ConvertConversationToTaskRequest draft,
    boolean confirmationRequired
) {
}
