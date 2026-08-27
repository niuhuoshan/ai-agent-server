package group.aitools.nhs.platform.identity.web;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 封装接口应用相关的不可变数据。
 * API application projection with its scope ceiling and no credential material. */
public record ApiApplicationView(
    Long id,
    String appKey,
    String name,
    String appType,
    String status,
    Long ownerId,
    String callbackUrl,
    Set<String> scopes,
    Map<String, Object> config,
    LocalDateTime expiresAt,
    LocalDateTime createdAt
) {
}
