package group.aitools.nhs.platform.conversation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装Retry会话会话回合相关的不可变数据。
 * Starts a new owner-bound turn from a retryable failed trace. */
public record RetryConversationTurnRequest(
    @NotBlank @Size(max = 128) String idempotencyKey
) {
}
