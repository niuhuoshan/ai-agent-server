package group.aitools.nhs.platform.conversation.persistence.row;

/**
 * 设置会话回合Status。
 *
 * 获取会话回合Status。
 *
 * 设置链路追踪Id。
 *
 * 获取链路追踪Id。
 *
 * 设置用户Id。
 *
 * 获取用户Id。
 *
 * 设置会话Id。
 *
 * 获取会话Id。
 *
 * 表示会话历史记录Target相关的领域对象。
 * Owner-bound conversation/turn target used by V1 history deletion. */
public class ConversationHistoryTargetRow {

    private Long conversationId;
    private Long userId;
    private String traceId;
    private String turnStatus;

    public Long getConversationId() { return conversationId; }

    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }

    public Long getUserId() { return userId; }

    public void setUserId(Long userId) { this.userId = userId; }

    public String getTraceId() { return traceId; }

    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getTurnStatus() { return turnStatus; }

    public void setTurnStatus(String turnStatus) { this.turnStatus = turnStatus; }
}
