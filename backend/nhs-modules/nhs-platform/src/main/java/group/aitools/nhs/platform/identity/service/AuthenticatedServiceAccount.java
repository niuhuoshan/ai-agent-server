package group.aitools.nhs.platform.identity.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;

import java.util.Set;

/**
 * 封装AuthenticatedService账户相关的不可变数据。
 * Verified machine principal and the credential-specific API scope available to a gateway. */
public record AuthenticatedServiceAccount(
    CurrentPrincipal principal,
    Long applicationId,
    String applicationKey,
    String applicationType,
    Long credentialId,
    Set<String> scopes
) {

    /**
     * 创建 {@code AuthenticatedServiceAccount} 实例并初始化所需依赖。
     *
     * @param principal 当前操作主体
     * @param applicationId 资源标识
     * @param applicationKey 应用Key参数
     * @param applicationType 业务类型
     * @param credentialId 资源标识
     * @param scopes {@code scopes}参数
     */
    public AuthenticatedServiceAccount {
        scopes = Set.copyOf(scopes);
    }
}
