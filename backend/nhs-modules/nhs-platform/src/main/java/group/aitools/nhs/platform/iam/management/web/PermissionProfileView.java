package group.aitools.nhs.platform.iam.management.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装权限配置档案相关的不可变数据。
 * One immutable permission profile version and its explicit rules. */
public record PermissionProfileView(
    Long id,
    String profileKey,
    String name,
    String description,
    String profileType,
    Integer versionNo,
    String status,
    Long createdBy,
    LocalDateTime createdAt,
    List<PermissionRuleView> entries
) {
}
