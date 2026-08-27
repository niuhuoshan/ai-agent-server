package group.aitools.nhs.platform.conversation.web;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Create会话相关的不可变数据。
 * Creates a private personal conversation. */
public record CreateConversationRequest(
    @Size(max = 255) String title,
    @Positive Long projectId,
    @Positive Long agentId,
    @Positive Long agentVersionId
) {
}
