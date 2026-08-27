package group.aitools.nhs.platform.scenario.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * 封装Scenario模板Uninstall相关的不可变数据。
 * Confirmation and idempotency contract for disabling an installed scenario. */
public record ScenarioTemplateUninstallRequest(
    @AssertTrue(message = "必须确认卸载场景实例") boolean confirm,
    @Size(max = 1000) String reason,
    @Size(max = 128) String idempotencyKey
) {
    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的场景卸载字段：" + field);
    }
}
