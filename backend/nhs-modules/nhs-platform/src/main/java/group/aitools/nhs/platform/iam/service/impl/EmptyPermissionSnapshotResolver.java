package group.aitools.nhs.platform.iam.service.impl;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionSnapshot;
import group.aitools.nhs.platform.iam.service.PermissionSnapshotResolver;

/**
 * 获取{@code resolve}。
 *
 * 负责Empty权限快照相关的转换、解析或处理逻辑。
 * Secure default used until the database-backed resolver is enabled. */
public final class EmptyPermissionSnapshotResolver implements PermissionSnapshotResolver {

    @Override
    public PermissionSnapshot resolve(CurrentPrincipal principal, PermissionContext context) {
        return PermissionSnapshot.empty();
    }
}
