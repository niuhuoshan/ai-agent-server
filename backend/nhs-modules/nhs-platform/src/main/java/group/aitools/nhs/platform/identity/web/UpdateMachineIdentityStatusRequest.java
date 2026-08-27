package group.aitools.nhs.platform.identity.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 封装UpdateMachine身份Status相关的不可变数据。
 * Enables, disables or permanently revokes a machine identity. */
public record UpdateMachineIdentityStatusRequest(
    @NotNull @Pattern(regexp = "active|disabled|revoked") String status
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的机器身份状态字段：" + field);
    }
}
