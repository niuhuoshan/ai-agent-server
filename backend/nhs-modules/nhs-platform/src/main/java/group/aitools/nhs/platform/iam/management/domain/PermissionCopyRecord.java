package group.aitools.nhs.platform.iam.management.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示权限Copy相关的领域对象。
 * Auditable result of copying or saving a reusable user permission baseline. */
@Data
public class PermissionCopyRecord {

    private Long id;
    private Long sourceUserId;
    private Long targetUserId;
    private Long sourceProfileId;
    private Integer sourceProfileVersion;
    private String copyMode;
    private Long beforeBindingId;
    private Long afterBindingId;
    private String diffJson;
    private String excludedJson;
    private String idempotencyKey;
    private Long createdBy;
    private LocalDateTime createdAt;
}
