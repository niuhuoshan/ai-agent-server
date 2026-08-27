package group.aitools.nhs.platform.report.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 封装Create报表Subscription相关的不可变数据。
 */
public record CreateReportSubscriptionRequest(
    @NotBlank @Pattern(regexp = "cron|interval") String scheduleType,
    @Size(max = 128) String cronExpr,
    @Min(1) @Max(525600) Integer intervalMinutes,
    @Size(max = 64) String timezone,
    @Size(max = 32768) String paramsJson,
    @Size(max = 32768) String notifyPolicyJson,
    @Min(1) @Max(10) Integer maxAttempts
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
