package group.aitools.nhs.platform.iam.management.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示{@code TemporaryGrant}相关的领域对象。
 * Expiring exceptional authorization that is never copied as a user baseline. */
@Data
public class TemporaryGrant {

    private Long id;
    private Long userId;
    private String resourceType;
    private Long resourceId;
    private String resourceKey;
    private String action;
    private String effect;
    private String policyJson;
    private String reason;
    private Long approvalId;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
