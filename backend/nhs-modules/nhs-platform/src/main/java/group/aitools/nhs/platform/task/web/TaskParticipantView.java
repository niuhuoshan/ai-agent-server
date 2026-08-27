package group.aitools.nhs.platform.task.web;

import group.aitools.nhs.platform.task.domain.AgentTaskParticipant;

import java.time.LocalDateTime;

/**
 * 封装任务Participant相关的不可变数据。
 * Public task participant projection. */
public record TaskParticipantView(
    Long id,
    Long taskId,
    Long userId,
    String type,
    String source,
    String status,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param participant {@code participant}参数
     * @return 处理结果
     */
    public static TaskParticipantView from(AgentTaskParticipant participant) {
        return new TaskParticipantView(
            participant.getId(), participant.getTaskId(), participant.getUserId(),
            participant.getParticipantType(), participant.getSource(), participant.getStatus(),
            participant.getCreatedAt()
        );
    }
}
