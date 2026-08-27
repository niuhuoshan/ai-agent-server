package group.aitools.nhs.web.domain.bo;

import jakarta.validation.constraints.NotBlank;

/**
 * 封装Refresh令牌Body相关的不可变数据。
 * Existing Sa-Token bearer token used to renew an active private-session lease. */
public record RefreshTokenBody(
    @NotBlank(message = "刷新令牌不能为空") String refreshToken
) {
}
