package group.aitools.nhs.platform.identity.web;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 封装接口凭据相关的不可变数据。
 * Credential metadata projection that deliberately omits both hash and secret. */
public record ApiCredentialView(
    Long id,
    Long applicationId,
    Long serviceAccountId,
    String keyPrefix,
    Set<String> scopes,
    String status,
    LocalDateTime lastUsedAt,
    LocalDateTime expiresAt,
    LocalDateTime createdAt
) {
}
