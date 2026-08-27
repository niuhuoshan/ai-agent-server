package group.aitools.nhs.platform.approval.web;

/**
 * 封装审批Decision相关的不可变数据。
 * Idempotent approval decision result. */
public record ApprovalDecisionResult(
    ApprovalView approval,
    boolean replayed,
    boolean runtimeResumed
) {
}
