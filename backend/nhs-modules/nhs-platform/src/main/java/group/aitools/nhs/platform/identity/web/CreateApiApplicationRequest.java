package group.aitools.nhs.platform.identity.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装Create接口应用相关的不可变数据。
 * Registers an integration application and its maximum API scope. */
public record CreateApiApplicationRequest(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+") String appKey,
    @NotBlank @Size(max = 128) String name,
    @Pattern(regexp = "embed|open_api|webhook|internal") String appType,
    @Positive Long ownerId,
    @Size(max = 1024) String callbackUrl,
    @Size(min = 1, max = 32) List<@NotBlank @Size(max = 64) String> scopes,
    LocalDateTime expiresAt,
    Map<String, Object> config
) {

    /**
     * 创建 {@code CreateApiApplicationRequest} 实例并初始化所需依赖。
     *
     * @param appKey {@code appKey}参数
     * @param name 名称
     * @param appType 业务类型
     * @param ownerId 资源标识
     * @param callbackUrl {@code callbackUrl}参数
     * @param scopes {@code scopes}参数
     * @param expiresAt {@code expiresAt}参数
     * @param config {@code config}参数
     */
    public CreateApiApplicationRequest {
        appType = appType == null ? "open_api" : appType;
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    /**
     * 创建 {@code CreateApiApplicationRequest} 实例并初始化所需依赖。
     *
     * @param appKey {@code appKey}参数
     * @param name 名称
     * @param appType 业务类型
     * @param ownerId 资源标识
     * @param callbackUrl {@code callbackUrl}参数
     * @param scopes {@code scopes}参数
     * @param expiresAt {@code expiresAt}参数
     */
    public CreateApiApplicationRequest(
        String appKey,
        String name,
        String appType,
        Long ownerId,
        String callbackUrl,
        List<String> scopes,
        LocalDateTime expiresAt
    ) {
        this(appKey, name, appType, ownerId, callbackUrl, scopes, expiresAt, Map.of());
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的API应用字段：" + field);
    }
}
