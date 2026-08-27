package group.aitools.nhs.platform.provider;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表示External智能体提供方Registry相关的领域对象。
 * Optional external-agent adapter registry; an empty registry is a valid deployment. */
@Component
public class ExternalAgentProviderRegistry {

    private final Map<String, ExternalAgentProvider> providers;

    public ExternalAgentProviderRegistry(List<ExternalAgentProvider> candidates) {
        Map<String, ExternalAgentProvider> result = new LinkedHashMap<>();
        for (ExternalAgentProvider provider : candidates) {
            String type = normalize(provider.providerType());
            if ("agentscope_java".equals(type) || result.putIfAbsent(type, provider) != null) {
                throw new IllegalStateException("外部Agent Provider类型重复或保留：" + type);
            }
        }
        providers = Map.copyOf(result);
    }

    /**
     * 校验{@code require}，并在条件不满足时终止处理。
     *
     * @param providerType 业务类型
     * @return 处理结果
     */
    public ExternalAgentProvider require(String providerType) {
        String type = normalize(providerType);
        ExternalAgentProvider provider = providers.get(type);
        if (provider == null) {
            throw new ServiceException("外部Agent Provider未配置：" + type, 503);
        }
        return provider;
    }

    /**
     * 处理{@code available}并返回对应结果。
     *
     * @param providerType 业务类型
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean available(String providerType) {
        return providers.containsKey(normalize(providerType));
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("providerType must not be blank");
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
