package group.aitools.nhs.platform.automation.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装Manual自动化Fire相关的不可变数据。
 */
public record ManualAutomationFireRequest(
    @NotBlank @Size(max = 128) String idempotencyKey,
    @Size(max = 131072) String input
) {
    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的手动触发字段：" + field);
    }
}
