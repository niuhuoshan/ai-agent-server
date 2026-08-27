package group.aitools.nhs.platform.task.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体任务Participant相关的领域对象。
 * Explicit task operation relation; it never grants tool or data capability. */
@Data
public class AgentTaskParticipant {

    private Long id;
    private Long taskId;
    private Long userId;
    private String participantType;
    private String source;
    private String status;
    private LocalDateTime createdAt;
}
