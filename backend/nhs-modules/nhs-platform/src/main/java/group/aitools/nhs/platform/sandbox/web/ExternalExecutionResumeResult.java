package group.aitools.nhs.platform.sandbox.web;

/**
 * 封装External执行Resume相关的不可变数据。
 * Server-owned identity returned after an external execution is claimed. */
public record ExternalExecutionResumeResult(
    Long taskId,
    Long runId,
    Long stepId,
    boolean replayed
) {
}
