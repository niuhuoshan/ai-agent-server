package group.aitools.nhs.platform.iam.management.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示用户权限Override相关的领域对象。
 * Stable user-specific rule layered over the base permission binding. */
@Data
public class UserPermissionOverride {

    private Long id;
    private Long userId;
    private String resourceType;
    private Long resourceId;
    private String resourceKey;
    private String action;
    private String effect;
    private String policyJson;
    private String reason;
    private String status;
    private LocalDateTime expiresAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
