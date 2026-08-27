package group.aitools.nhs.platform.identity.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示接口凭据相关的领域对象。
 * Hashed API credential; the raw secret is never represented by this persistence model. */
@Data
public class ApiCredential {

    private Long id;
    private Long applicationId;
    private Long serviceAccountId;
    private String keyPrefix;
    private String secretHash;
    private String scopeJson;
    private LocalDateTime lastUsedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
