package group.aitools.nhs.platform.execution.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装Create任务Run相关的不可变数据。
 * Starts a new immutable execution attempt from the task's current submitted version. */
public record CreateTaskRunRequest(
    @NotBlank @Size(max = 128) String idempotencyKey,
    @NotBlank @Size(max = 100_000) String input
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的任务运行字段：" + field);
    }
}
