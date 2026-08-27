package group.aitools.nhs.platform.identity.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装UpdateService账户相关的不可变数据。
 * Updates non-secret service-account metadata while preserving its stable key. */
public record UpdateServiceAccountRequest(
    @NotBlank @Size(max = 128) String name,
    @Size(max = 2000) String description,
    @Positive Long ownerId,
    LocalDateTime expiresAt,
    Map<String, Object> metadata
) {

    /**
     * 创建 {@code UpdateServiceAccountRequest} 实例并初始化所需依赖。
     *
     * @param name 名称
     * @param description {@code description}参数
     * @param ownerId 资源标识
     * @param expiresAt {@code expiresAt}参数
     * @param metadata 元数据参数
     */
    public UpdateServiceAccountRequest {
        metadata = metadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的服务账号更新字段：" + field);
    }
}
