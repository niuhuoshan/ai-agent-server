package group.aitools.nhs.platform.task.web;

import group.aitools.nhs.platform.task.domain.AgentTaskAccessRule;

import java.time.LocalDateTime;

/**
 * 封装任务AccessRule相关的不可变数据。
 * Active task ACL projection without internal permission-resolution evidence. */
public record TaskAccessRuleView(
    Long id,
    Long taskId,
    String subjectType,
    Long subjectId,
    String subjectKey,
    String action,
    String effect,
    LocalDateTime expiresAt,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param rule {@code rule}参数
     * @return 处理结果
     */
    public static TaskAccessRuleView from(AgentTaskAccessRule rule) {
        return new TaskAccessRuleView(
            rule.getId(), rule.getTaskId(), rule.getSubjectType(), rule.getSubjectId(),
            rule.getSubjectKey(), rule.getAction(), rule.getEffect(), rule.getExpiresAt(),
            rule.getCreatedAt()
        );
    }
}
