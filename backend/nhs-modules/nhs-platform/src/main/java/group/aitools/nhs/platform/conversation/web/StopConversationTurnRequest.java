package group.aitools.nhs.platform.conversation.web;

import jakarta.validation.constraints.Size;

/**
 * 封装Stop会话会话回合相关的不可变数据。
 * Optional owner-supplied reason for stopping an active conversation turn. */
public record StopConversationTurnRequest(@Size(max = 500) String reason) {
}
