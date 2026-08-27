package group.aitools.nhs.platform.task.web;

/**
 * 封装任务Conversion相关的不可变数据。
 * Result of an idempotent conversation-to-task conversion. */
public record TaskConversionResult(Long taskId, Long taskVersionId, boolean replayed) {
}
