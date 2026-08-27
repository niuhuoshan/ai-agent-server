package group.aitools.nhs.platform.task.web;

import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;

/**
 * 封装任务Visibility相关的不可变数据。
 * Explains the current principal's task visibility without exposing ACL internals. */
public record TaskVisibilityView(
    Long taskId,
    String visibility,
    boolean viewable,
    String reasonCode,
    String reason
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param visibility {@code visibility}参数
     * @param decision {@code decision}参数
     * @return 处理结果
     */
    public static TaskVisibilityView from(
        Long taskId,
        String visibility,
        AuthorizationDecision decision
    ) {
        return new TaskVisibilityView(
            taskId,
            visibility,
            decision.allowed(),
            decision.reasonCode(),
            decision.reason()
        );
    }
}
