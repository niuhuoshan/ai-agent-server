package group.aitools.nhs.platform.report.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 封装Update报表SubscriptionStatus相关的不可变数据。
 */
public record UpdateReportSubscriptionStatusRequest(
    @NotBlank @Pattern(regexp = "active|paused") String status
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的报表订阅字段：" + field);
    }
}
