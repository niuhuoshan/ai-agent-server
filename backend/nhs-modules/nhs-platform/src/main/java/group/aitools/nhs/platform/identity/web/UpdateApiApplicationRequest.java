package group.aitools.nhs.platform.identity.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装Update接口应用相关的不可变数据。
 * Updates an API application's owner, callback and maximum scope. */
public record UpdateApiApplicationRequest(
    @NotBlank @Size(max = 128) String name,
    @Positive Long ownerId,
    @Size(max = 1024) String callbackUrl,
    @Size(min = 1, max = 32) List<@NotBlank @Size(max = 64) String> scopes,
    LocalDateTime expiresAt,
    Map<String, Object> config
) {

    /**
     * 创建 {@code UpdateApiApplicationRequest} 实例并初始化所需依赖。
     *
     * @param name 名称
     * @param ownerId 资源标识
     * @param callbackUrl {@code callbackUrl}参数
     * @param scopes {@code scopes}参数
     * @param expiresAt {@code expiresAt}参数
     * @param config {@code config}参数
     */
    public UpdateApiApplicationRequest {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    /**
     * 创建 {@code UpdateApiApplicationRequest} 实例并初始化所需依赖。
     *
     * @param name 名称
     * @param ownerId 资源标识
     * @param callbackUrl {@code callbackUrl}参数
     * @param scopes {@code scopes}参数
     * @param expiresAt {@code expiresAt}参数
     */
    public UpdateApiApplicationRequest(
        String name,
        Long ownerId,
        String callbackUrl,
        List<String> scopes,
        LocalDateTime expiresAt
    ) {
        this(name, ownerId, callbackUrl, scopes, expiresAt, Map.of());
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的API应用更新字段：" + field);
    }
}
