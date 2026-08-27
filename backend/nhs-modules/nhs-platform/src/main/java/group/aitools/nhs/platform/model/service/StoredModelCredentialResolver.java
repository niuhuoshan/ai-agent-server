package group.aitools.nhs.platform.model.service;

import org.springframework.stereotype.Component;

/**
 * 获取{@code resolve}。
 *
 * 负责Stored模型凭据相关的转换、解析或处理逻辑。
 * Reads the provider API key stored with the model registry record. */
@Component
public class StoredModelCredentialResolver implements ModelCredentialResolver {

    private static final int MAX_API_KEY_LENGTH = 8192;

    @Override
    public String resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("模型 API Key 未配置");
        }
        String normalized = apiKey.strip();
        if (normalized.length() > MAX_API_KEY_LENGTH) {
            throw new IllegalArgumentException("模型 API Key 超过长度限制");
        }
        if (normalized.startsWith("v1s.") || normalized.startsWith("env:")) {
            throw new IllegalStateException("模型 API Key 使用旧存储格式，请重新填写");
        }
        return normalized;
    }
}
