package group.aitools.nhs.platform.iam.management.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * 封装权限RuleInput相关的不可变数据。
 * Explicit capability rule accepted by profile, snapshot and override operations. */
public record PermissionRuleInput(
    @NotBlank @Size(max = 32) String resourceType,
    @Positive Long resourceId,
    @Size(max = 255) String resourceKey,
    @NotBlank @Size(max = 32) String action,
    @NotBlank @Pattern(regexp = "allow|deny|approval_required") String effect,
    Map<String, Object> policy,
    @Size(max = 1000) String reason
) {

    /**
     * 创建 {@code PermissionRuleInput} 实例并初始化所需依赖。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param resourceKey 资源Key参数
     * @param action {@code action}参数
     * @param effect {@code effect}参数
     * @param policy 策略参数
     * @param reason {@code reason}参数
     */
    public PermissionRuleInput {
        policy = policy == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(policy));
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的权限规则字段：" + field);
    }
}
