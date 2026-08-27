package group.aitools.nhs.runtime.spi;

/**
 * 封装运行时Cancellation相关的不可变数据。
 */
public record RuntimeCancellationResult(boolean activeExecutionFound, boolean interruptRequested) {
}
