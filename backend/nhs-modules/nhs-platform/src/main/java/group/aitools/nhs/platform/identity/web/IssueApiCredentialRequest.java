package group.aitools.nhs.platform.identity.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装Issue接口凭据相关的不可变数据。
 * Issues one credential bound to a service account and a narrowed application scope. */
public record IssueApiCredentialRequest(
    @NotNull @Positive Long serviceAccountId,
    @Size(min = 1, max = 32) List<String> scopes,
    LocalDateTime expiresAt
) {

    /**
     * 创建 {@code IssueApiCredentialRequest} 实例并初始化所需依赖。
     *
     * @param serviceAccountId 资源标识
     * @param scopes {@code scopes}参数
     * @param expiresAt {@code expiresAt}参数
     */
    public IssueApiCredentialRequest {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的API凭证字段：" + field);
    }
}
