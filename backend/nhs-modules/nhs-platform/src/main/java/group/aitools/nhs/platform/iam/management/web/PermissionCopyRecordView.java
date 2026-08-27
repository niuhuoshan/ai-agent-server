package group.aitools.nhs.platform.iam.management.web;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装权限CopyRecord相关的不可变数据。
 * Audit projection for one reference-user permission copy operation. */
public record PermissionCopyRecordView(
    Long id,
    Long sourceUserId,
    Long targetUserId,
    Long sourceProfileId,
    Integer sourceProfileVersion,
    String copyMode,
    Long beforeBindingId,
    Long afterBindingId,
    Map<String, Object> diff,
    Map<String, Object> excluded,
    String idempotencyKey,
    Long createdBy,
    LocalDateTime createdAt
) {
}
