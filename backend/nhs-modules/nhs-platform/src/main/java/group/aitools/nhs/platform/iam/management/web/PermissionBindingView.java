package group.aitools.nhs.platform.iam.management.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装权限Binding相关的不可变数据。
 * Active base profile or immutable copied snapshot bound to one user. */
public record PermissionBindingView(
    Long id,
    Long userId,
    String bindingType,
    Long profileId,
    Integer profileVersion,
    Long sourceUserId,
    String status,
    Long createdBy,
    LocalDateTime createdAt,
    List<PermissionRuleView> snapshotRules
) {
}
