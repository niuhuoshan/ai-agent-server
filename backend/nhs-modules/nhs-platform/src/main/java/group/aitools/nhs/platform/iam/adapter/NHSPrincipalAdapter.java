package group.aitools.nhs.platform.iam.adapter;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.system.api.model.LoginUser;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 表示NHS操作主体相关的领域对象。
 * Adapts only identity fields from the NHS login object into the platform principal. */
public final class NHSPrincipalAdapter {

    private static final String SERVICE_ACCOUNT_TYPE = "service_account";
    private static final String LEGACY_SUPER_ADMIN_ROLE = "superadmin";

    private NHSPrincipalAdapter() {
    }

    /**
     * 处理{@code adapt}并返回对应结果。
     *
     * @param loginUser 登录用户参数
     * @return 处理结果
     */
    public static CurrentPrincipal adapt(LoginUser loginUser) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Objects.requireNonNull(loginUser, "loginUser must not be null");
        Long userId = Objects.requireNonNull(loginUser.getUserId(), "login user id must not be null");
        String username = loginUser.getUsername();
        if (username == null || username.isBlank()) {
            username = "user-" + userId;
        }

        Set<String> roleKeys = loginUser.getRolePermission() == null
            ? Set.of()
            : loginUser.getRolePermission();
        boolean serviceAccount = isServiceAccount(loginUser.getUserType(), roleKeys);
        if (serviceAccount) {
            return new CurrentPrincipal(
                userId,
                username,
                PrincipalType.SERVICE_ACCOUNT,
                Set.of(PlatformRole.SERVICE_ACCOUNT)
            );
        }

        Set<PlatformRole> roles = new LinkedHashSet<>();
        for (String roleKey : roleKeys) {
            PlatformRole.fromKey(roleKey).ifPresent(roles::add);
            if (roleKey != null && LEGACY_SUPER_ADMIN_ROLE.equals(roleKey.trim().toLowerCase(Locale.ROOT))) {
                roles.add(PlatformRole.PLATFORM_ADMIN);
            }
        }
        roles.remove(PlatformRole.SERVICE_ACCOUNT);
        roles.add(PlatformRole.MEMBER);
        return new CurrentPrincipal(userId, username, PrincipalType.HUMAN, roles);
    }

    /**
     * 判断Service账户是否满足要求。
     *
     * @param userType 业务类型
     * @param roleKeys 角色Keys参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean isServiceAccount(String userType, Collection<String> roleKeys) {
        if (SERVICE_ACCOUNT_TYPE.equalsIgnoreCase(userType)) {
            return true;
        }
        return roleKeys.stream().anyMatch(role ->
            role != null && PlatformRole.SERVICE_ACCOUNT.key().equalsIgnoreCase(role.trim())
        );
    }
}
