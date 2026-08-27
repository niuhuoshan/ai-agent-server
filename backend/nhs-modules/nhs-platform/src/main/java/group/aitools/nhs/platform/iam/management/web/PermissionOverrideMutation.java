package group.aitools.nhs.platform.iam.management.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;

/**
 * 封装权限OverrideMutation相关的不可变数据。
 * One deterministic upsert or revoke operation for a user-specific override. */
public record PermissionOverrideMutation(
    @NotNull @Pattern(regexp = "upsert|revoke") String operation,
    @NotNull @Valid PermissionRuleInput rule,
    LocalDateTime expiresAt
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的权限覆盖字段：" + field);
    }
}
