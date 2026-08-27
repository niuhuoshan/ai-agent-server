package group.aitools.nhs.platform.iam.domain;

import java.util.Objects;
import java.util.Set;

/**
 * 封装当前操作主体相关的不可变数据。
 * Organization-independent identity used by platform authorization. */
public record CurrentPrincipal(
    Long id,
    String username,
    PrincipalType type,
    Set<PlatformRole> roles
) {

    /**
     * 创建 {@code CurrentPrincipal} 实例并初始化所需依赖。
     *
     * @param id 资源标识
     * @param username 名称
     * @param type 业务类型
     * @param roles {@code roles}参数
     */
    public CurrentPrincipal {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Objects.requireNonNull(id, "principal id must not be null");
        if (id <= 0) {
            throw new IllegalArgumentException("principal id must be positive");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        Objects.requireNonNull(type, "principal type must not be null");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("at least one platform role is required");
        }
        if (type == PrincipalType.SERVICE_ACCOUNT && !roles.contains(PlatformRole.SERVICE_ACCOUNT)) {
            throw new IllegalArgumentException("service principals require the service_account role");
        }
    }

    /**
     * 判断角色是否满足要求。
     *
     * @param role 角色参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean hasRole(PlatformRole role) {
        return roles.contains(role);
    }

    /**
     * 判断{@code Human}是否满足要求。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean isHuman() {
        return type == PrincipalType.HUMAN;
    }
}
