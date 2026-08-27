package group.aitools.nhs.platform.iam.management.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 封装{@code CreateTemporaryGrant}相关的不可变数据。
 * Creates a bounded, expiring exception without changing the stable baseline. */
public record CreateTemporaryGrantRequest(
    @NotNull @Valid PermissionRuleInput rule,
    @NotBlank @Size(max = 1000) String reason,
    @Positive Long approvalId,
    @NotNull LocalDateTime expiresAt
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的临时授权字段：" + field);
    }
}
