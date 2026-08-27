package group.aitools.nhs.platform.automation.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装Create自动化Trigger相关的不可变数据。
 */
public record CreateAutomationTriggerRequest(
    @NotBlank @Size(max = 128) String triggerKey,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 20) String triggerType,
    @NotNull @Positive Long taskId,
    @NotNull @Positive Long taskVersionId,
    @NotNull @Positive Long serviceAccountId,
    @Size(max = 128) String cronExpression,
    @Size(max = 64) String timezone,
    @Size(max = 16) String misfirePolicy,
    @Min(1) @Max(10) Integer maxCatchupCount,
    @Min(1) @Max(10) Integer maxAttempts,
    @Size(max = 131072) String inputTemplate,
    Map<String, Object> config
) {
    /**
     * 创建 {@code CreateAutomationTriggerRequest} 实例并初始化所需依赖。
     *
     * @param triggerKey {@code triggerKey}参数
     * @param name 名称
     * @param triggerType 业务类型
     * @param taskId 资源标识
     * @param taskVersionId 资源标识
     * @param serviceAccountId 资源标识
     * @param cronExpression {@code cronExpression}参数
     * @param timezone {@code timezone}参数
     * @param misfirePolicy misfire策略参数
     * @param maxCatchupCount {@code maxCatchupCount}参数
     * @param maxAttempts {@code maxAttempts}参数
     * @param inputTemplate input模板参数
     * @param config {@code config}参数
     */
    public CreateAutomationTriggerRequest {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的自动化触发器字段：" + field);
    }
}
