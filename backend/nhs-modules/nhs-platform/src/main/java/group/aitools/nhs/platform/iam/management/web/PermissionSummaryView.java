package group.aitools.nhs.platform.iam.management.web;

import java.util.List;

/**
 * 封装权限Summary相关的不可变数据。
 * Explainable user permission composition before runtime resource intersections. */
public record PermissionSummaryView(
    Long userId,
    PermissionBindingView binding,
    List<PermissionRuleView> baseRules,
    List<PermissionRuleView> overrides,
    List<PermissionRuleView> temporaryGrants
) {
}
