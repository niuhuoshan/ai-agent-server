package group.aitools.nhs.platform.provider;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表示External知识库提供方Registry相关的领域对象。
 * Discovers optional external knowledge adapters without making them startup dependencies. */
@Component
public class ExternalKnowledgeProviderRegistry {

    private static final String LOCAL_PROVIDER = "postgres_pgvector";
    private static final String RAGFLOW_PROVIDER = "ragflow";

    private final Map<String, ExternalKnowledgeProvider> providers;

    public ExternalKnowledgeProviderRegistry(List<ExternalKnowledgeProvider> candidates) {
        Map<String, ExternalKnowledgeProvider> result = new LinkedHashMap<>();
        for (ExternalKnowledgeProvider provider : candidates) {
            String type = normalize(provider.providerType());
            if (LOCAL_PROVIDER.equals(type) || result.putIfAbsent(type, provider) != null) {
                throw new IllegalStateException("外部知识Provider类型重复或保留：" + type);
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
    public ExternalKnowledgeProvider require(String providerType) {
        String type = normalize(providerType);
        ExternalKnowledgeProvider provider = providers.get(type);
        if (provider == null) {
            throw new ServiceException("外部知识Provider未配置：" + type, 503);
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
 * 处理{@code statuses}并返回对应结果。
 *
     * Publishes a safe, credential-free view of provider readiness for the
     * platform UI and deployment checks. The local pgvector implementation is
     * built in; external adapters are ready only when a Spring bean is present.
     */
    public List<ProviderStatus> statuses() {
        List<ProviderStatus> result = new ArrayList<>();
        result.add(new ProviderStatus(
            LOCAL_PROVIDER, true, "ready", "平台内置 PostgreSQL/pgvector 知识库"
        ));
        result.add(new ProviderStatus(
            RAGFLOW_PROVIDER,
            providers.containsKey(RAGFLOW_PROVIDER),
            providers.containsKey(RAGFLOW_PROVIDER) ? "ready" : "not_configured",
            providers.containsKey(RAGFLOW_PROVIDER)
                ? "RAGFlow Provider 已加载"
                : "未配置 RAGFlow Provider，迁移数据不能直接检索"
        ));
        providers.forEach((type, ignored) -> {
            if (!LOCAL_PROVIDER.equals(type) && !RAGFLOW_PROVIDER.equals(type)) {
                result.add(new ProviderStatus(type, true, "ready", "外部知识 Provider 已加载"));
            }
        });
        return Collections.unmodifiableList(result);
    }

    /**
     * 封装提供方Status相关的不可变数据。
     */
    public record ProviderStatus(
        String providerType,
        boolean available,
        String state,
        String message
    ) {
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
