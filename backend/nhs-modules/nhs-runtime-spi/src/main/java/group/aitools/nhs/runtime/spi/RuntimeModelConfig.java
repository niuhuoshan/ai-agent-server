package group.aitools.nhs.runtime.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/**
 * 封装运行时模型相关的不可变数据。
 * Model configuration whose database credential reference is resolved only at runtime. */
public record RuntimeModelConfig(
    String provider,
    String modelName,
    String baseUrl,
    String credentialRef,
    Map<String, Object> options
) {

    /**
     * 创建 {@code RuntimeModelConfig} 实例并初始化所需依赖。
     *
     * @param provider 提供方参数
     * @param modelName 名称
     * @param baseUrl {@code baseUrl}参数
     * @param credentialRef 凭据Ref参数
     * @param options {@code options}参数
     */
    public RuntimeModelConfig {
        provider = requireText(provider, "provider");
        modelName = requireText(modelName, "modelName");
        baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.strip();
        credentialRef = requireText(credentialRef, "credentialRef");
        options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    /**
     * 校验{@code Text}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param name 名称
     * @return 处理结果
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
