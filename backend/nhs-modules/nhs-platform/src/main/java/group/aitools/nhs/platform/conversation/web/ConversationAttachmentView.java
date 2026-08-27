package group.aitools.nhs.platform.conversation.web;

import group.aitools.nhs.platform.conversation.domain.AgentConversationAttachment;

import java.time.LocalDateTime;

/**
 * 封装会话附件相关的不可变数据。
 * Safe attachment projection without its local storage reference. */
public record ConversationAttachmentView(
    Long id,
    Long conversationId,
    Long turnId,
    String originalName,
    String mimeType,
    long sizeBytes,
    String sha256,
    String status,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param attachment 附件参数
     * @return 处理结果
     */
    public static ConversationAttachmentView from(AgentConversationAttachment attachment) {
        return new ConversationAttachmentView(
            attachment.getId(), attachment.getConversationId(), attachment.getTurnId(),
            attachment.getOriginalName(), attachment.getMimeType(), attachment.getSizeBytes(),
            attachment.getSha256(), attachment.getStatus(), attachment.getCreatedAt()
        );
    }
}
