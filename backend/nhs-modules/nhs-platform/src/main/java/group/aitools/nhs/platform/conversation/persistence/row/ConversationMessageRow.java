package group.aitools.nhs.platform.conversation.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示会话消息相关的领域对象。
 * Persistence-only projection for private conversation history. */
@Data
public class ConversationMessageRow {

    private Long id;
    private Long conversationId;
    private Integer sequenceNo;
    private String traceId;
    private String role;
    private String content;
    private Long agentId;
    private Long agentVersionId;
    private Long modelId;
    private String status;
    /** Latest owner-scoped feedback rating when a projection explicitly joins it. */
    private String feedback;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private LocalDateTime createdAt;
}
