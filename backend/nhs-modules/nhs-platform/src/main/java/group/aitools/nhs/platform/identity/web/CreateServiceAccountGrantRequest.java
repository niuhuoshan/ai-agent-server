package group.aitools.nhs.platform.identity.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 封装CreateService账户Grant相关的不可变数据。
 * Creates one explicit machine capability; absence remains a deny. */
public record CreateServiceAccountGrantRequest(
    @NotBlank @Size(max = 32) String resourceType,
    Long resourceId,
    @Size(max = 255) String resourceKey,
    @NotBlank @Size(max = 32) String action,
    @NotBlank @Size(max = 24) String effect,
    @NotBlank @Size(max = 1000) String reason,
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
        throw new IllegalArgumentException("不支持的服务账号授权字段：" + field);
    }
}
