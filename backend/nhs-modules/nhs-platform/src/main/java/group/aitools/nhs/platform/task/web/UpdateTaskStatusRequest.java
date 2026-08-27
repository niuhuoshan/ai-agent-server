package group.aitools.nhs.platform.task.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 封装Update任务Status相关的不可变数据。
 * Explicit human-controlled task lifecycle transition. */
public record UpdateTaskStatusRequest(
    @NotBlank @Pattern(regexp = "draft|ready|scheduled|rework|blocked|cancelled|archived") String status
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的任务状态字段：" + field);
    }
}
