package group.aitools.nhs.platform.iam.service.impl;

import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.DecisionEvidence;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PermissionSource;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.domain.TaskVisibility;
import group.aitools.nhs.platform.iam.service.AuthorizationService;
import group.aitools.nhs.platform.iam.service.TaskVisibilityService;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 负责Default任务Visibility相关的业务编排与领域规则处理。
 * Implements enterprise-readable tasks while keeping restricted tasks explicit. */
public final class DefaultTaskVisibilityService implements TaskVisibilityService {

    private final AuthorizationService authorizationService;

    public DefaultTaskVisibilityService(AuthorizationService authorizationService) {
        this.authorizationService = Objects.requireNonNull(
            authorizationService, "authorizationService must not be null"
        );
    }

    /**
     * 处理{@code authorizeView}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param taskId 资源标识
     * @param artifactId 资源标识
     * @param visibility {@code visibility}参数
     * @return 处理结果
     */
    @Override
    public AuthorizationDecision authorizeView(
        CurrentPrincipal principal,
        Long taskId,
        Long artifactId,
        TaskVisibility visibility
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");

        String resourceType = artifactId == null ? "task" : "artifact";
        Long resourceId = artifactId == null ? taskId : artifactId;
        PermissionContext context = new PermissionContext(
            resourceType,
            resourceId,
            null,
            "view",
            ResourceState.ACTIVE,
            false,
            Set.of(),
            taskId
        );
        AuthorizationDecision decision = authorizationService.authorize(principal, context);

        if (decision.effect() == PermissionEffect.DENY && !"DEFAULT_DENY".equals(decision.reasonCode())) {
            return decision;
        }
        if (decision.requiresApproval()) {
            return decision;
        }
        if (visibility == TaskVisibility.ENTERPRISE_SHARED && principal.isHuman()) {
            return visibleByEnterprisePolicy();
        }
        if (visibility == TaskVisibility.ENTERPRISE_SHARED && isExplicitAllow(decision)) {
            return decision;
        }
        if (visibility == TaskVisibility.ENTERPRISE_SHARED) {
            return decision;
        }
        if (visibility == TaskVisibility.RESTRICTED && isRestrictedAllow(decision)) {
            return decision;
        }
        return restrictedAccessRuleRequired();
    }

    /**
     * 处理visibleByEnterprise策略并返回对应结果。
     *
     * @return 处理结果
     */
    private AuthorizationDecision visibleByEnterprisePolicy() {
        String reason = "Enterprise members can read non-restricted tasks.";
        return new AuthorizationDecision(
            PermissionEffect.ALLOW,
            "ENTERPRISE_SHARED_VISIBLE",
            reason,
            List.of(new DecisionEvidence(
                PermissionSource.DEFAULT_POLICY, "enterprise_shared", PermissionEffect.ALLOW, reason
            ))
        );
    }

    /**
     * 判断{@code RestrictedAllow}是否满足要求。
     *
     * @param decision {@code decision}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isRestrictedAllow(AuthorizationDecision decision) {
        return decision.allowed()
            && Set.of("EXPLICIT_ALLOW", "PLATFORM_ADMIN_ALLOWED").contains(decision.reasonCode());
    }

    /**
     * 判断{@code ExplicitAllow}是否满足要求。
     *
     * @param decision {@code decision}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isExplicitAllow(AuthorizationDecision decision) {
        return decision.allowed() && "EXPLICIT_ALLOW".equals(decision.reasonCode());
    }

    /**
     * 处理{@code restrictedAccessRuleRequired}并返回对应结果。
     *
     * @return 处理结果
     */
    private AuthorizationDecision restrictedAccessRuleRequired() {
        String reason = "Restricted tasks require an explicit access rule or the platform administrator role.";
        return new AuthorizationDecision(
            PermissionEffect.DENY,
            "RESTRICTED_ACCESS_RULE_REQUIRED",
            reason,
            List.of(new DecisionEvidence(
                PermissionSource.DEFAULT_POLICY,
                "restricted",
                PermissionEffect.DENY,
                reason
            ))
        );
    }
}
