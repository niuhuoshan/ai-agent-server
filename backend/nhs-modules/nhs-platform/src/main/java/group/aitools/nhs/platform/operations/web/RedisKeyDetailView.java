package group.aitools.nhs.platform.operations.web;

/**
 * 封装{@code RedisKeyDetail}相关的不可变数据。
 * Bounded, sanitized value inspection for one Redis key. */
public record RedisKeyDetailView(
    String name,
    String type,
    long ttlSeconds,
    Object value,
    boolean valueTruncated
) {
}
