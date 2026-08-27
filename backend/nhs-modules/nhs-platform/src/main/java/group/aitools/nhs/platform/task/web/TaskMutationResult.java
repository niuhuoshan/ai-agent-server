package group.aitools.nhs.platform.task.web;

/**
 * 封装任务Mutation相关的不可变数据。
 * Idempotent formal task mutation result. */
public record TaskMutationResult(TaskView task, Long taskVersionId, boolean replayed) {
}
