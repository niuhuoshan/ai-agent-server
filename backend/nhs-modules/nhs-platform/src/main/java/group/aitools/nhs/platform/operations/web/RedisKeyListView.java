package group.aitools.nhs.platform.operations.web;

import java.util.List;

/**
 * 封装{@code RedisKeyList}相关的不可变数据。
 * Bounded Redis key scan result. */
public record RedisKeyListView(
    long totalCount,
    int returnedCount,
    boolean truncated,
    String pattern,
    List<RedisKeyView> keys
) {
}
