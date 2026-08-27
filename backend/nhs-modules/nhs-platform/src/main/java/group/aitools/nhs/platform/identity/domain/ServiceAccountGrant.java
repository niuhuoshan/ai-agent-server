package group.aitools.nhs.platform.identity.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Service账户Grant相关的领域对象。
 * Resource capability assigned directly to one isolated service account. */
@Data
public class ServiceAccountGrant {

    private Long id;
    private Long serviceAccountId;
    private String resourceType;
    private Long resourceId;
    private String resourceKey;
    private String action;
    private String effect;
    private String reason;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
