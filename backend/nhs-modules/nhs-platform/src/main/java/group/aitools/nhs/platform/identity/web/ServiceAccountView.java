package group.aitools.nhs.platform.identity.web;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装Service账户相关的不可变数据。
 * Safe service-account projection that never contains a credential. */
public record ServiceAccountView(
    Long id,
    String accountKey,
    String name,
    String description,
    Long ownerId,
    String status,
    LocalDateTime lastUsedAt,
    LocalDateTime expiresAt,
    Map<String, Object> metadata,
    LocalDateTime createdAt
) {
}
