package group.aitools.nhs.platform.execution.web;

/**
 * 封装任务RunAction相关的不可变数据。
 * Idempotent create/start/cancel result. */
public record TaskRunActionResult(TaskRunView run, boolean replayed) {
}
