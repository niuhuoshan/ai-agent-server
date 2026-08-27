package group.aitools.nhs.platform.embed.web;

import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;

import java.time.LocalDateTime;

/**
 * 封装嵌入式会话消息相关的不可变数据。
 */
public record EmbedMessageView(
    Long id,
    String traceId,
    String role,
    String content,
    String status,
    String feedback,
    LocalDateTime createdAt
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    public static EmbedMessageView from(ConversationMessageRow row) {
        return new EmbedMessageView(
            row.getId(), row.getTraceId(), row.getRole(), row.getContent(),
            row.getStatus(), row.getFeedback(), row.getCreatedAt()
        );
    }
}
