package group.aitools.nhs.platform.operations.web;

/**
 * 封装{@code RedisMutation}相关的不可变数据。
 * Result of a destructive Redis maintenance operation. */
public record RedisMutationView(
    String status,
    long affectedCount,
    long preservedCount,
    String message
) {
}
