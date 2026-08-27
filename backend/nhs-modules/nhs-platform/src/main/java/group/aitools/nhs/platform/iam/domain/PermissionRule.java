package group.aitools.nhs.platform.iam.domain;

import java.util.Objects;

/**
 * 封装权限Rule相关的不可变数据。
 * One effective rule produced by profile, override, temporary grant or snapshot resolution. */
public record PermissionRule(
    String resourceType,
    Long resourceId,
    String resourceKey,
    String action,
    PermissionEffect effect,
    PermissionSource source,
    String sourceReference,
    String reason
) {

    /**
     * 创建 {@code PermissionRule} 实例并初始化所需依赖。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param resourceKey 资源Key参数
     * @param action {@code action}参数
     * @param effect {@code effect}参数
     * @param source 数据源参数
     * @param sourceReference 数据源Reference参数
     * @param reason {@code reason}参数
     */
    public PermissionRule {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType must not be blank");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        resourceType = resourceType.trim();
        resourceKey = resourceKey == null || resourceKey.isBlank() ? null : resourceKey.trim();
        action = action.trim();
        Objects.requireNonNull(effect, "effect must not be null");
        Objects.requireNonNull(source, "source must not be null");
        sourceReference = sourceReference == null ? "" : sourceReference;
        reason = reason == null ? "" : reason;
    }

    /**
     * 判断{@code matches}是否满足要求。
     *
     * @param context 待处理内容
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean matches(PermissionContext context) {
        if (!("*".equals(resourceType) || resourceType.equals(context.resourceType()))) {
            return false;
        }
        if (!("*".equals(action) || action.equals(context.action()))) {
            return false;
        }
        if (resourceId != null && !resourceId.equals(context.resourceId())) {
            return false;
        }
        return resourceKey == null || resourceKey.equals(context.resourceKey());
    }
}
