package group.aitools.nhs.platform.operations.web;

import jakarta.validation.constraints.AssertTrue;

/**
 * 封装{@code RedisFlush}相关的不可变数据。
 * Clear non-conversation cache keys while preserving user conversation data. */
public record RedisFlushRequest(
    @AssertTrue(message = "清理 Redis 需要明确确认")
    boolean confirm,
    boolean preserveConversations
) {
}
