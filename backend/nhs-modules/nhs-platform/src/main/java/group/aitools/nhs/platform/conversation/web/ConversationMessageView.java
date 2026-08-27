package group.aitools.nhs.platform.conversation.web;

import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;

import java.time.LocalDateTime;

/**
 * 封装会话消息相关的不可变数据。
 * Owner-only conversation message projection without internal JSON metadata. */
public record ConversationMessageView(
    Long id,
    Long conversationId,
    int sequenceNo,
    String traceId,
    String role,
    String content,
    Long agentId,
    Long agentVersionId,
    Long modelId,
    String status,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    public static ConversationMessageView from(ConversationMessageRow row) {
        return new ConversationMessageView(
            row.getId(), row.getConversationId(), value(row.getSequenceNo()), row.getTraceId(),
            row.getRole(), row.getContent(), row.getAgentId(), row.getAgentVersionId(),
            row.getModelId(), row.getStatus(), value(row.getPromptTokens()),
            value(row.getCompletionTokens()), value(row.getTotalTokens()), row.getCreatedAt()
        );
    }

    /**
     * 处理{@code value}并返回对应结果。
     *
     * @param number {@code number}参数
     * @return 处理结果
     */
    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
