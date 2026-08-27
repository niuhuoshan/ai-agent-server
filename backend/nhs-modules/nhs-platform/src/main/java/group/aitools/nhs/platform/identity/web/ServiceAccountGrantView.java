package group.aitools.nhs.platform.identity.web;

import java.time.LocalDateTime;

/**
 * 封装Service账户Grant相关的不可变数据。
 */
public record ServiceAccountGrantView(
    Long id,
    Long serviceAccountId,
    String resourceType,
    Long resourceId,
    String resourceKey,
    String action,
    String effect,
    String reason,
    LocalDateTime expiresAt,
    LocalDateTime revokedAt,
    LocalDateTime createdAt
) {
}
