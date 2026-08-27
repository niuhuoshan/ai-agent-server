package group.aitools.nhs.platform.iam.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionSnapshot;

/**
 * 获取{@code resolve}。
 *
 * 定义权限快照相关的处理能力契约。
 * Resolves profile, override, temporary grant and task snapshot rules. */
public interface PermissionSnapshotResolver {

    PermissionSnapshot resolve(CurrentPrincipal principal, PermissionContext context);
}
