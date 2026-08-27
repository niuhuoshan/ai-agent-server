package group.aitools.nhs.platform.conversation.web;

import group.aitools.nhs.platform.conversation.domain.AgentChatFeedback;

import java.time.LocalDateTime;

/**
 * 封装会话反馈相关的不可变数据。
 */
public record ConversationFeedbackView(
    Long id,
    Long conversationId,
    Long messageId,
    Long turnId,
    String rating,
    String reason,
    String comment,
    String traceId,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static ConversationFeedbackView from(AgentChatFeedback value) {
        return new ConversationFeedbackView(
            value.getId(), value.getConversationId(), value.getMessageId(), value.getTurnId(),
            value.getRating(), value.getReason(), value.getComment(), value.getTraceId(),
            value.getCreatedAt(), value.getUpdatedAt()
        );
    }
}
