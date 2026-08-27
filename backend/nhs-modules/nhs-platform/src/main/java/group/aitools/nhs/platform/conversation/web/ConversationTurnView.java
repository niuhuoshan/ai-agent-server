package group.aitools.nhs.platform.conversation.web;

import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;

import java.time.LocalDateTime;

/**
 * 封装会话会话回合相关的不可变数据。
 * Public owner-only projection of a durable conversation turn. */
public record ConversationTurnView(
    Long id,
    Long conversationId,
    String traceId,
    Long agentId,
    Long agentVersionId,
    String status,
    boolean replayed,
    LocalDateTime startedAt,
    LocalDateTime finishedAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param turn 会话回合参数
     * @param replayed {@code replayed}参数
     * @return 处理结果
     */
    public static ConversationTurnView from(AgentConversationTurn turn, boolean replayed) {
        return new ConversationTurnView(
            turn.getId(), turn.getConversationId(), turn.getTraceId(), turn.getAgentId(),
            turn.getAgentVersionId(), turn.getStatus(), replayed,
            turn.getStartedAt(), turn.getFinishedAt()
        );
    }
}
