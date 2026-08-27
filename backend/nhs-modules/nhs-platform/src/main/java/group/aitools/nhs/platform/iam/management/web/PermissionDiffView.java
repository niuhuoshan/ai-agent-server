package group.aitools.nhs.platform.iam.management.web;

import java.util.List;

/**
 * 封装权限Diff相关的不可变数据。
 * Stable reusable-rule difference between a reference user and target user. */
public record PermissionDiffView(
    Long sourceUserId,
    Long targetUserId,
    List<PermissionRuleView> missingOnTarget,
    List<PermissionRuleView> targetOnly,
    List<PermissionRuleView> changed,
    List<PermissionRuleView> excludedFromCopy
) {
}
