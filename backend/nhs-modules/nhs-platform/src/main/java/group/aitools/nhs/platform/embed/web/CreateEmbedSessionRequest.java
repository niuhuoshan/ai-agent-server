package group.aitools.nhs.platform.embed.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Create嵌入式会话会话相关的不可变数据。
 */
public record CreateEmbedSessionRequest(
    @NotNull @Positive Long agentVersionId,
    @NotBlank @Size(max = 256) String externalUserKey,
    @Min(5) @Max(1440) Integer expiresInMinutes
) {
    /**
     * 创建 {@code CreateEmbedSessionRequest} 实例并初始化所需依赖。
     *
     * @param agentVersionId 资源标识
     * @param externalUserKey external用户Key参数
     * @param expiresInMinutes {@code expiresInMinutes}参数
     */
    public CreateEmbedSessionRequest {
        expiresInMinutes = expiresInMinutes == null ? 60 : expiresInMinutes;
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的Embed会话字段：" + field);
    }
}
