package group.aitools.nhs.platform.iam.management.web;

import java.util.List;

/**
 * 封装权限Copy相关的不可变数据。
 * Idempotent copy result with before/after references and explainable exclusions. */
public record PermissionCopyResult(
    Long copyRecordId,
    Long sourceUserId,
    Long targetUserId,
    String copyMode,
    Long beforeBindingId,
    Long afterBindingId,
    Long createdProfileId,
    Integer createdProfileVersion,
    int addedRuleCount,
    int retainedRuleCount,
    List<PermissionRuleView> excludedRules,
    boolean replayed
) {
}
