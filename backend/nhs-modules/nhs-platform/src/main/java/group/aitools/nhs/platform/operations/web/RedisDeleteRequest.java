package group.aitools.nhs.platform.operations.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装{@code RedisDelete}相关的不可变数据。
 * Explicit confirmation is required for batch deletion. */
public record RedisDeleteRequest(
    @NotEmpty(message = "至少选择一个 Redis 键")
    @Size(max = 5000, message = "一次最多删除 5000 个 Redis 键")
    List<String> keys,
    @AssertTrue(message = "删除 Redis 键需要明确确认")
    boolean confirm
) {
}
