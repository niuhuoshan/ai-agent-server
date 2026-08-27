package group.aitools.nhs.platform.operations.web;

/**
 * 封装{@code RedisKey}相关的不可变数据。
 * Sanitized Redis key metadata exposed to platform administrators. */
public record RedisKeyView(
    String name,
    String type,
    long ttlSeconds
) {
}
