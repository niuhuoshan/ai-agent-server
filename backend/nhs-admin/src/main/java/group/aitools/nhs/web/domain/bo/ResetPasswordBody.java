package group.aitools.nhs.web.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.common.core.constant.RegexConstants;

/**
 * 封装{@code ResetPasswordBody}相关的不可变数据。
 * Unauthenticated self-service password reset request verified by SMS. */
public record ResetPasswordBody(
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = RegexConstants.MOBILE, message = "手机号格式不正确")
    String phoneNumber,
    @NotBlank(message = "短信验证码不能为空")
    @Size(min = 4, max = 8, message = "短信验证码格式不正确")
    String smsCode,
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 30, message = "密码长度必须在6到30位之间")
    String newPassword
) {
}
