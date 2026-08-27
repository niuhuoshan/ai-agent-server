package group.aitools.nhs.platform.embed.web;

import group.aitools.nhs.platform.embed.domain.EmbedTurn;

import java.time.LocalDateTime;

/**
 * 封装嵌入式会话会话回合相关的不可变数据。
 */
public record EmbedTurnView(
    Long id,
    String status,
    String errorSummary,
    LocalDateTime stopRequestedAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param turn 会话回合参数
     * @return 处理结果
     */
    public static EmbedTurnView from(EmbedTurn turn) {
        return new EmbedTurnView(
            turn.getId(), turn.getStatus(), turn.getErrorSummary(), turn.getStopRequestedAt(),
            turn.getStartedAt(), turn.getFinishedAt()
        );
    }
}
