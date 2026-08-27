package group.aitools.nhs.platform.embed.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Issue嵌入式会话浏览器凭据相关的不可变数据。
 */
public record IssueEmbedBrowserCredentialRequest(
    @NotBlank @Size(max = 512) String origin,
    @NotNull @Positive Long agentVersionId,
    @NotBlank @Size(max = 256) String externalUserKey,
    @Min(1) @Max(1440) Integer sessionMinutes
) {
    /**
     * 创建 {@code IssueEmbedBrowserCredentialRequest} 实例并初始化所需依赖。
     *
     * @param origin {@code origin}参数
     * @param agentVersionId 资源标识
     * @param externalUserKey external用户Key参数
     * @param sessionMinutes 会话Minutes参数
     */
    public IssueEmbedBrowserCredentialRequest {
        sessionMinutes = sessionMinutes == null ? 60 : sessionMinutes;
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的Embed浏览器凭证字段：" + field);
    }
}
