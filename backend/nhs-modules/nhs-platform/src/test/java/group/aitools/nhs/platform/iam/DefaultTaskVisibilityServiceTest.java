package group.aitools.nhs.platform.iam;

import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PermissionRule;
import group.aitools.nhs.platform.iam.domain.PermissionSnapshot;
import group.aitools.nhs.platform.iam.domain.PermissionSource;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.domain.TaskVisibility;
import group.aitools.nhs.platform.iam.service.impl.DefaultAuthorizationService;
import group.aitools.nhs.platform.iam.service.impl.DefaultTaskVisibilityService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class DefaultTaskVisibilityServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal SERVICE = new CurrentPrincipal(
        201L, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );

    @Test
    void enterpriseSharedTaskIsReadableByHumanMember() {
        DefaultTaskVisibilityService service = new DefaultTaskVisibilityService(
            new DefaultAuthorizationService((principal, context) -> PermissionSnapshot.empty())
        );

        AuthorizationDecision decision = service.authorizeView(MEMBER, 1L, null, TaskVisibility.ENTERPRISE_SHARED);

        assertTrue(decision.allowed());
        assertEquals("ENTERPRISE_SHARED_VISIBLE", decision.reasonCode());
    }

    @Test
    void serviceAccountNeedsExplicitRuleEvenForEnterpriseTask() {
        DefaultTaskVisibilityService service = new DefaultTaskVisibilityService(
            new DefaultAuthorizationService((principal, context) -> PermissionSnapshot.empty())
        );

        AuthorizationDecision decision = service.authorizeView(SERVICE, 1L, null, TaskVisibility.ENTERPRISE_SHARED);

        assertEquals(PermissionEffect.DENY, decision.effect());
        assertEquals("DEFAULT_DENY", decision.reasonCode());
    }

    @Test
    void restrictedTaskUsesTaskAccessRuleResolver() {
        DefaultTaskVisibilityService service = new DefaultTaskVisibilityService(
            new DefaultAuthorizationService((principal, context) -> new PermissionSnapshot(
                "task-rule-1", List.of(new PermissionRule(
                    "task", 2L, null, "view", PermissionEffect.ALLOW,
                    PermissionSource.TASK_ACCESS_RULE, "acl-2", "explicit task ACL"
                ))
            ))
        );

        AuthorizationDecision decision = service.authorizeView(MEMBER, 2L, null, TaskVisibility.RESTRICTED);

        assertTrue(decision.allowed());
        assertEquals("EXPLICIT_ALLOW", decision.reasonCode());
    }

    @Test
    void restrictedTaskWithoutAclIsDeniedDespiteMemberBaseline() {
        DefaultTaskVisibilityService service = new DefaultTaskVisibilityService(
            new DefaultAuthorizationService((principal, context) -> PermissionSnapshot.empty())
        );

        AuthorizationDecision decision = service.authorizeView(MEMBER, 2L, null, TaskVisibility.RESTRICTED);

        assertEquals(PermissionEffect.DENY, decision.effect());
        assertEquals("RESTRICTED_ACCESS_RULE_REQUIRED", decision.reasonCode());
    }

    @Test
    void explicitDenyOverridesEnterpriseSharedVisibility() {
        DefaultTaskVisibilityService service = new DefaultTaskVisibilityService(
            new DefaultAuthorizationService((principal, context) -> new PermissionSnapshot(
                "deny-shared", List.of(new PermissionRule(
                    "task", 4L, null, "view", PermissionEffect.DENY,
                    PermissionSource.TASK_ACCESS_RULE, "acl-deny-4", "explicit deny"
                ))
            ))
        );

        AuthorizationDecision decision = service.authorizeView(
            MEMBER, 4L, null, TaskVisibility.ENTERPRISE_SHARED
        );

        assertEquals(PermissionEffect.DENY, decision.effect());
        assertEquals("EXPLICIT_DENY", decision.reasonCode());
    }

    @Test
    void serviceAccountCanReadEnterpriseTaskOnlyWithExplicitAcl() {
        DefaultTaskVisibilityService service = new DefaultTaskVisibilityService(
            new DefaultAuthorizationService((principal, context) -> new PermissionSnapshot(
                "service-acl", List.of(new PermissionRule(
                    "task", 5L, null, "view", PermissionEffect.ALLOW,
                    PermissionSource.TASK_ACCESS_RULE, "acl-service-5", "explicit service ACL"
                ))
            ))
        );

        AuthorizationDecision decision = service.authorizeView(
            SERVICE, 5L, null, TaskVisibility.ENTERPRISE_SHARED
        );

        assertTrue(decision.allowed());
        assertEquals("EXPLICIT_ALLOW", decision.reasonCode());
    }

    @Test
    void platformAdminCanGovernRestrictedTask() {
        CurrentPrincipal admin = new CurrentPrincipal(
            1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN, PlatformRole.MEMBER)
        );
        DefaultTaskVisibilityService service = new DefaultTaskVisibilityService(
            new DefaultAuthorizationService((principal, context) -> PermissionSnapshot.empty())
        );

        AuthorizationDecision decision = service.authorizeView(admin, 3L, null, TaskVisibility.RESTRICTED);

        assertTrue(decision.allowed());
        assertEquals("PLATFORM_ADMIN_ALLOWED", decision.reasonCode());
    }

    @Test
    void explicitDenyAlsoOverridesPlatformAdministrator() {
        CurrentPrincipal admin = new CurrentPrincipal(
            1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN, PlatformRole.MEMBER)
        );
        DefaultTaskVisibilityService service = new DefaultTaskVisibilityService(
            new DefaultAuthorizationService((principal, context) -> new PermissionSnapshot(
                "admin-deny", List.of(new PermissionRule(
                    "task", 6L, null, "view", PermissionEffect.DENY,
                    PermissionSource.TASK_ACCESS_RULE, "acl-admin-deny-6", "emergency restriction"
                ))
            ))
        );

        AuthorizationDecision decision = service.authorizeView(admin, 6L, null, TaskVisibility.RESTRICTED);

        assertEquals(PermissionEffect.DENY, decision.effect());
        assertEquals("EXPLICIT_DENY", decision.reasonCode());
    }
}
