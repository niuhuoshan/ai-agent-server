package group.aitools.nhs.platform.iam.domain;

import java.util.Objects;
import java.util.Set;

/**
 * 封装权限相关的不可变数据。
 * Full input required for one authorization decision. */
public record PermissionContext(
    String resourceType,
    Long resourceId,
    String resourceKey,
    String action,
    ResourceState resourceState,
    boolean userInterfaceOperation,
    Set<BusinessRelation> relations,
    Long taskId
) {

    /**
     * 创建 {@code PermissionContext} 实例并初始化所需依赖。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param resourceKey 资源Key参数
     * @param action {@code action}参数
     * @param resourceState 资源State参数
     * @param userInterfaceOperation 用户Interface操作参数
     * @param relations {@code relations}参数
     * @param taskId 资源标识
     */
    public PermissionContext {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType must not be blank");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        resourceType = resourceType.trim();
        resourceKey = resourceKey == null || resourceKey.isBlank() ? null : resourceKey.trim();
        action = action.trim();
        Objects.requireNonNull(resourceState, "resourceState must not be null");
        relations = Set.copyOf(Objects.requireNonNull(relations, "relations must not be null"));
        if (taskId != null && taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
    }

    /**
     * 创建 {@code PermissionContext} 实例并初始化所需依赖。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param resourceKey 资源Key参数
     * @param action {@code action}参数
     * @param resourceState 资源State参数
     * @param userInterfaceOperation 用户Interface操作参数
     * @param relations {@code relations}参数
     */
    public PermissionContext(
        String resourceType,
        Long resourceId,
        String resourceKey,
        String action,
        ResourceState resourceState,
        boolean userInterfaceOperation,
        Set<BusinessRelation> relations
    ) {
        this(resourceType, resourceId, resourceKey, action, resourceState, userInterfaceOperation, relations, null);
    }

    /**
     * 处理{@code active}并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param action {@code action}参数
     * @return 处理结果
     */
    public static PermissionContext active(String resourceType, Long resourceId, String action) {
        return new PermissionContext(
            resourceType, resourceId, null, action, ResourceState.ACTIVE, false, Set.of(), null
        );
    }
}
