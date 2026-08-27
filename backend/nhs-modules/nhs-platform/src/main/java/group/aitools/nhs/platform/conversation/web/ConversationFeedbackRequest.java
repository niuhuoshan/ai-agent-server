package group.aitools.nhs.platform.conversation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装会话反馈相关的不可变数据。
 * User feedback for one assistant message. */
public record ConversationFeedbackRequest(
    Long messageId,
    Long turnId,
    @NotBlank String rating,
    @Size(max = 64) String reason,
    @Size(max = 2000) String comment,
    String traceId
) {
}
