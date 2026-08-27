package group.aitools.nhs.platform.task.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体任务AccessRule相关的领域对象。
 * Explicit allow or deny rule for a restricted task. */
@Data
public class AgentTaskAccessRule {

    private Long id;
    private Long taskId;
    private Long artifactId;
    private String subjectType;
    private Long subjectId;
    private String subjectKey;
    private String action;
    private String effect;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
