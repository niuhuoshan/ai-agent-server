package group.aitools.nhs.platform.execution.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;

/**
 * 处理{@code rejectUnknownField}相关逻辑。
 *
 * 封装Cancel任务Run相关的不可变数据。
 * Human cancellation reason; runtime identifiers are always loaded from the server snapshot. */
public record CancelTaskRunRequest(@Size(max = 2000) String reason) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的取消运行字段：" + field);
    }
}
