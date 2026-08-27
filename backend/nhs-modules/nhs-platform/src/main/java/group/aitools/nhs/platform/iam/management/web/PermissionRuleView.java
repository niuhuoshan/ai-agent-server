package group.aitools.nhs.platform.iam.management.web;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装权限Rule相关的不可变数据。
 * Safe permission rule projection without credentials or raw execution state. */
public record PermissionRuleView(
    Long id,
    String resourceType,
    Long resourceId,
    String resourceKey,
    String action,
    String effect,
    Map<String, Object> policy,
    String reason,
    String status,
    LocalDateTime expiresAt,
    String resourceState
) {
}
