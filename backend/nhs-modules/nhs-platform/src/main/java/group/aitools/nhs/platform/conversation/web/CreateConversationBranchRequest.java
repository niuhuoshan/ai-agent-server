package group.aitools.nhs.platform.conversation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Create会话Branch相关的不可变数据。
 * Creates an owner-bound conversation branch and makes one idempotent regeneration request. */
public record CreateConversationBranchRequest(
    @Positive Long forkMessageId,
    @NotBlank @Size(max = 128) String idempotencyKey
) {
}
