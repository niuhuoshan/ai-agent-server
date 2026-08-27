package group.aitools.nhs.platform.iam;

import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PermissionRule;
import group.aitools.nhs.platform.iam.domain.PermissionSnapshot;
import group.aitools.nhs.platform.iam.domain.PermissionSource;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.impl.DefaultAuthorizationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class DefaultAuthorizationServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal SERVICE_ACCOUNT = new CurrentPrincipal(
        201L, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );
    private static final CurrentPrincipal PLATFORM_ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    @Test
    void deniesByDefaultWhenNoEffectiveRuleMatches() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            PermissionSnapshot.empty()
        );

        AuthorizationDecision decision = service.authorize(MEMBER, PermissionContext.active("tool", 1L, "invoke"));

        assertEquals(PermissionEffect.DENY, decision.effect());
        assertEquals("DEFAULT_DENY", decision.reasonCode());
    }

    @Test
    void memberBaselineCannotInvokeTools() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            PermissionSnapshot.empty()
        );

        AuthorizationDecision decision = service.authorize(
            MEMBER, PermissionContext.active("tool", 1L, "invoke")
        );

        assertEquals(PermissionEffect.DENY, decision.effect());
        assertEquals("DEFAULT_DENY", decision.reasonCode());
    }

    @Test
    void memberCanReadAndUpdateOwnActiveConversationState() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            PermissionSnapshot.empty()
        );

        AuthorizationDecision read = service.authorize(
            MEMBER, PermissionContext.active("conversation", null, "view_active")
        );
        AuthorizationDecision update = service.authorize(
            MEMBER, PermissionContext.active("conversation", 7L, "update_active")
        );

        assertTrue(read.allowed());
        assertTrue(update.allowed());
    }

    @Test
    void explicitDenyOverridesMemberBaselineTaskView() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            new PermissionSnapshot("deny-task", List.of(new PermissionRule(
                "task", 9L, null, "view", PermissionEffect.DENY,
                PermissionSource.TASK_ACCESS_RULE, "acl-deny-9", "restricted task"
            )))
        );

        AuthorizationDecision decision = service.authorize(
            MEMBER, PermissionContext.active("task", 9L, "view")
        );

        assertEquals(PermissionEffect.DENY, decision.effect());
        assertEquals("EXPLICIT_DENY", decision.reasonCode());
    }

    @Test
    void serviceAccountCannotUseHumanUiEvenWithExplicitAllow() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            new PermissionSnapshot("service-allow", List.of(new PermissionRule(
                "task", 9L, null, "view", PermissionEffect.ALLOW,
                PermissionSource.TASK_ACCESS_RULE, "acl-service-9", "explicit service account ACL"
            )))
        );
        PermissionContext uiContext = new PermissionContext(
            "task", 9L, null, "view", ResourceState.ACTIVE, true, Set.of()
        );

        AuthorizationDecision decision = service.authorize(SERVICE_ACCOUNT, uiContext);

        assertEquals(PermissionEffect.DENY, decision.effect());
        assertEquals("SERVICE_ACCOUNT_UI_FORBIDDEN", decision.reasonCode());
    }

    @Test
    void denyOverridesApprovalAndAllowRules() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            new PermissionSnapshot("profile-3", List.of(
                new PermissionRule(
                    "tool", 1L, null, "invoke", PermissionEffect.ALLOW,
                    PermissionSource.PROFILE, "profile-entry-1", "profile allow"
                ),
                new PermissionRule(
                    "tool", 1L, null, "invoke", PermissionEffect.APPROVAL_REQUIRED,
                    PermissionSource.NHS_POLICY, "agent-policy-1", "approval required"
                ),
                new PermissionRule(
                    "tool", 1L, null, "invoke", PermissionEffect.DENY,
                    PermissionSource.USER_OVERRIDE, "override-1", "blocked for this user"
                )
            ))
        );

        AuthorizationDecision decision = service.authorize(MEMBER, PermissionContext.active("tool", 1L, "invoke"));

        assertEquals(PermissionEffect.DENY, decision.effect());
        assertEquals("EXPLICIT_DENY", decision.reasonCode());
        assertEquals(3, decision.evidence().size());
    }

    @Test
    void approvalRuleIsReturnedToTheApprovalQueue() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            new PermissionSnapshot("profile-4", List.of(new PermissionRule(
                "tool", 2L, null, "invoke", PermissionEffect.APPROVAL_REQUIRED,
                PermissionSource.PROFILE, "profile-entry-2", "requires review"
            )))
        );

        AuthorizationDecision decision = service.authorize(MEMBER, PermissionContext.active("tool", 2L, "invoke"));

        assertTrue(decision.requiresApproval());
        assertEquals("APPROVAL_REQUIRED", decision.reasonCode());
    }

    @Test
    void ownerCanOperateTaskButCannotUseUnrelatedResources() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            PermissionSnapshot.empty()
        );

        AuthorizationDecision taskDecision = service.authorize(MEMBER, new PermissionContext(
            "task", 3L, null, "operate", ResourceState.ACTIVE, false, Set.of(BusinessRelation.OWNER)
        ));
        AuthorizationDecision toolDecision = service.authorize(MEMBER, new PermissionContext(
            "tool", 3L, null, "invoke", ResourceState.ACTIVE, false, Set.of(BusinessRelation.OWNER)
        ));

        assertTrue(taskDecision.allowed());
        assertEquals(PermissionEffect.DENY, toolDecision.effect());
    }

    @Test
    void taskOwnerProjectAdminAndPlatformAdminCanAcceptButCollaboratorCannot() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            PermissionSnapshot.empty()
        );

        AuthorizationDecision owner = service.authorize(MEMBER, new PermissionContext(
            "task", 3L, null, "accept", ResourceState.ACTIVE, true, Set.of(BusinessRelation.OWNER)
        ));
        AuthorizationDecision projectAdmin = service.authorize(MEMBER, new PermissionContext(
            "task", 3L, null, "accept", ResourceState.ACTIVE, true, Set.of(BusinessRelation.PROJECT_ADMIN)
        ));
        AuthorizationDecision collaborator = service.authorize(MEMBER, new PermissionContext(
            "task", 3L, null, "accept", ResourceState.ACTIVE, true, Set.of(BusinessRelation.COLLABORATOR)
        ));
        AuthorizationDecision platformAdmin = service.authorize(PLATFORM_ADMIN, new PermissionContext(
            "task", 3L, null, "accept", ResourceState.ACTIVE, true, Set.of()
        ));

        assertTrue(owner.allowed());
        assertTrue(projectAdmin.allowed());
        assertEquals(PermissionEffect.DENY, collaborator.effect());
        assertTrue(platformAdmin.allowed());
    }

    @Test
    void inactiveResourceRejectsMutatingOperationBeforeRules() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            new PermissionSnapshot("profile-5", List.of(new PermissionRule(
                "tool", 4L, null, "invoke", PermissionEffect.ALLOW,
                PermissionSource.PROFILE, "profile-entry-4", "allowed"
            )))
        );

        AuthorizationDecision decision = service.authorize(MEMBER, new PermissionContext(
            "tool", 4L, null, "invoke", ResourceState.REVOKED, false, Set.of()
        ));

        assertEquals("RESOURCE_NOT_ACTIVE", decision.reasonCode());
    }

    @Test
    void memberCanViewOwnPermissionSummaryButOnlyAdminCanManageIam() {
        DefaultAuthorizationService service = new DefaultAuthorizationService((principal, context) ->
            PermissionSnapshot.empty()
        );
        PermissionContext selfView = new PermissionContext(
            "iam", 101L, null, "view", ResourceState.ACTIVE, true, Set.of(BusinessRelation.OWNER)
        );
        PermissionContext selfManage = new PermissionContext(
            "iam", 101L, null, "manage", ResourceState.ACTIVE, true, Set.of(BusinessRelation.OWNER)
        );

        assertTrue(service.authorize(MEMBER, selfView).allowed());
        assertEquals(PermissionEffect.DENY, service.authorize(MEMBER, selfManage).effect());
        assertTrue(service.authorize(PLATFORM_ADMIN, selfManage).allowed());
    }
}
