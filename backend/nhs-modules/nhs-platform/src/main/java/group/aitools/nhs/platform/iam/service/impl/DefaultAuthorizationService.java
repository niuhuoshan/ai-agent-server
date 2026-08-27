package group.aitools.nhs.platform.iam.service.impl;

import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.DecisionEvidence;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PermissionRule;
import group.aitools.nhs.platform.iam.domain.PermissionSnapshot;
import group.aitools.nhs.platform.iam.domain.PermissionSource;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationService;
import group.aitools.nhs.platform.iam.service.PermissionSnapshotResolver;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 负责Default授权相关的业务编排与领域规则处理。
 * Default-deny authorization engine with deny-first rule precedence. */
public final class DefaultAuthorizationService implements AuthorizationService {

    private static final Set<String> READ_ACTIONS = Set.of("view", "read", "list");
    private static final Set<String> PLATFORM_ADMIN_RESOURCE_TYPES = Set.of(
        "platform", "iam", "approval", "audit", "agent", "agent_version", "model",
        "tool", "skill", "memory", "report", "notification", "knowledge_base", "data_source",
        "dataset", "workflow", "project", "workspace", "conversation"
    );
    private static final Set<String> PLATFORM_ADMIN_ACTIONS = Set.of(
        "view", "read", "list", "create", "update", "delete", "manage", "admin",
        "approve", "reject", "revoke", "assign", "operate", "cancel", "retry", "export", "accept"
    );
    private static final Set<String> APPROVAL_ACTIONS = Set.of(
        "view", "read", "list", "approve", "reject", "takeover", "accept"
    );
    private static final Set<String> MEMBER_CONVERSATION_ACTIONS = Set.of(
        "create", "list", "view", "invoke", "stop", "upload_attachment", "feedback",
        "read_resource_scope", "update_resource_scope", "view_active", "update_active",
        "export", "delete", "delete_history"
    );
    private static final Set<String> MEMBER_PROJECT_ACTIONS = Set.of("create", "list");
    private static final Set<String> MEMBER_TASK_ACTIONS = Set.of("create", "list", "view");
    private static final Set<String> MEMBER_KNOWLEDGE_ACTIONS = Set.of("create", "list");
    private static final Set<String> MEMBER_REPORT_ACTIONS = Set.of("create", "list", "view");
    private static final Set<String> MEMBER_NOTIFICATION_ACTIONS = Set.of("list", "view", "read");
    private static final Set<String> OWNER_ACTIONS = Set.of(
        "view", "read", "comment", "operate", "admin", "cancel", "retry", "export", "accept"
    );
    private static final Set<String> PARTICIPANT_ACTIONS = Set.of("view", "read", "comment", "operate");

    private final PermissionSnapshotResolver snapshotResolver;

    /**
     * 创建 {@code DefaultAuthorizationService} 实例并初始化所需依赖。
     *
     * @param snapshotResolver 快照Resolver参数
     */
    public DefaultAuthorizationService(PermissionSnapshotResolver snapshotResolver) {
        this.snapshotResolver = Objects.requireNonNull(snapshotResolver, "snapshotResolver must not be null");
    }

    /**
     * 处理{@code authorize}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param context 待处理内容
     * @return 处理结果
     */
    @Override
    public AuthorizationDecision authorize(CurrentPrincipal principal, PermissionContext context) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(context, "context must not be null");

        if (!principal.isHuman() && context.userInterfaceOperation()) {
            return decision(
                PermissionEffect.DENY,
                "SERVICE_ACCOUNT_UI_FORBIDDEN",
                "Service accounts cannot access human user-interface operations.",
                PermissionSource.PLATFORM_ROLE,
                PlatformRole.SERVICE_ACCOUNT.key()
            );
        }

        if (context.resourceState() != ResourceState.ACTIVE && !READ_ACTIONS.contains(context.action())) {
            return decision(
                PermissionEffect.DENY,
                "RESOURCE_NOT_ACTIVE",
                "The requested operation requires an active resource.",
                PermissionSource.RESOURCE_STATE,
                context.resourceState().name()
            );
        }

        PermissionSnapshot snapshot = snapshotResolver.resolve(principal, context);
        List<PermissionRule> matchingRules = snapshot.rules().stream()
            .filter(rule -> rule.matches(context))
            .toList();

        AuthorizationDecision ruleDecision = evaluateRules(matchingRules);
        if (ruleDecision != null) {
            return ruleDecision;
        }

        if (isPlatformAdminOperation(principal, context)) {
            return decision(
                PermissionEffect.ALLOW,
                "PLATFORM_ADMIN_ALLOWED",
                "The fixed platform administrator role allows this management operation.",
                PermissionSource.PLATFORM_ROLE,
                PlatformRole.PLATFORM_ADMIN.key()
            );
        }

        if (isApprovalOperation(principal, context)) {
            return decision(
                PermissionEffect.ALLOW,
                "APPROVAL_ROLE_ALLOWED",
                "The fixed approval role allows this review operation.",
                PermissionSource.PLATFORM_ROLE,
                PlatformRole.APPROVAL_USER.key()
            );
        }

        if (isMemberBaselineOperation(principal, context)) {
            return decision(
                PermissionEffect.ALLOW,
                "MEMBER_BASELINE_ALLOWED",
                "The fixed member role allows this baseline platform operation.",
                PermissionSource.PLATFORM_ROLE,
                PlatformRole.MEMBER.key()
            );
        }

        if (isBusinessRelationAllowed(context)) {
            return decision(
                PermissionEffect.ALLOW,
                "BUSINESS_RELATION_ALLOWED",
                "The user's object-level relation allows this operation.",
                PermissionSource.BUSINESS_RELATION,
                context.relations().toString()
            );
        }

        return decision(
            PermissionEffect.DENY,
            "DEFAULT_DENY",
            "No effective permission allows this operation.",
            PermissionSource.DEFAULT_POLICY,
            "default-deny"
        );
    }

    /**
     * 处理{@code evaluateRules}并返回对应结果。
     *
     * @param matchingRules {@code matchingRules}参数
     * @return 处理结果
     */
    private AuthorizationDecision evaluateRules(List<PermissionRule> matchingRules) {
        if (matchingRules.isEmpty()) {
            return null;
        }
        List<DecisionEvidence> evidence = matchingRules.stream()
            .map(rule -> new DecisionEvidence(
                rule.source(), rule.sourceReference(), rule.effect(), rule.reason()
            ))
            .toList();

        if (matchingRules.stream().anyMatch(rule -> rule.effect() == PermissionEffect.DENY)) {
            return new AuthorizationDecision(
                PermissionEffect.DENY,
                "EXPLICIT_DENY",
                "An effective deny rule matched the request.",
                evidence
            );
        }
        if (matchingRules.stream().anyMatch(rule -> rule.effect() == PermissionEffect.APPROVAL_REQUIRED)) {
            return new AuthorizationDecision(
                PermissionEffect.APPROVAL_REQUIRED,
                "APPROVAL_REQUIRED",
                "The operation requires an approved request.",
                evidence
            );
        }
        return new AuthorizationDecision(
            PermissionEffect.ALLOW,
            "EXPLICIT_ALLOW",
            "An effective allow rule matched the request.",
            evidence
        );
    }

    /**
     * 判断平台Admin操作是否满足要求。
     *
     * @param principal 当前操作主体
     * @param context 待处理内容
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isPlatformAdminOperation(CurrentPrincipal principal, PermissionContext context) {
        if (!principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            return false;
        }
        if (PLATFORM_ADMIN_RESOURCE_TYPES.contains(context.resourceType())) {
            return true;
        }
        return Set.of("connector", "task", "artifact").contains(context.resourceType())
            && PLATFORM_ADMIN_ACTIONS.contains(context.action());
    }

    /**
     * 判断审批操作是否满足要求。
     *
     * @param principal 当前操作主体
     * @param context 待处理内容
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isApprovalOperation(CurrentPrincipal principal, PermissionContext context) {
        return principal.hasRole(PlatformRole.APPROVAL_USER)
            && Set.of("approval", "task").contains(context.resourceType())
            && APPROVAL_ACTIONS.contains(context.action());
    }

    /**
     * 判断{@code BusinessRelationAllowed}是否满足要求。
     *
     * @param context 待处理内容
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isBusinessRelationAllowed(PermissionContext context) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if ("iam".equals(context.resourceType())) {
            return context.relations().contains(BusinessRelation.OWNER)
                && READ_ACTIONS.contains(context.action());
        }
        if ("skill".equals(context.resourceType())) {
            Set<BusinessRelation> relations = context.relations();
            if (relations.contains(BusinessRelation.OWNER)
                || relations.contains(BusinessRelation.PROJECT_ADMIN)) {
                return Set.of(
                    "view", "read", "list", "use", "create", "update", "delete",
                    "admin", "publish", "archive"
                ).contains(context.action());
            }
            return (relations.contains(BusinessRelation.COLLABORATOR)
                || relations.contains(BusinessRelation.WATCHER))
                && Set.of("view", "read", "list", "use").contains(context.action());
        }
        if ("knowledge_base".equals(context.resourceType())) {
            Set<BusinessRelation> relations = context.relations();
            if (relations.contains(BusinessRelation.OWNER)) {
                return Set.of(
                    "view", "read", "list", "use", "update", "delete", "admin", "upload", "parse"
                ).contains(context.action());
            }
            return relations.contains(BusinessRelation.COLLABORATOR)
                && Set.of("view", "read", "list", "use").contains(context.action());
        }
        if ("dataset".equals(context.resourceType())) {
            Set<BusinessRelation> relations = context.relations();
            return relations.contains(BusinessRelation.OWNER)
                && Set.of(
                    "view", "read", "list", "query", "export", "export_sensitive",
                    "update", "delete", "admin", "sync"
                )
                    .contains(context.action());
        }
        if ("memory".equals(context.resourceType())) {
            Set<BusinessRelation> relations = context.relations();
            return relations.contains(BusinessRelation.OWNER)
                && Set.of("view", "read", "list", "create", "update", "delete", "review", "manage")
                    .contains(context.action());
        }
        if (!Set.of("project", "task", "artifact").contains(context.resourceType())) {
            return false;
        }
        Set<BusinessRelation> relations = context.relations();
        if ((relations.contains(BusinessRelation.OWNER) || relations.contains(BusinessRelation.PROJECT_ADMIN))
            && OWNER_ACTIONS.contains(context.action())) {
            return true;
        }
        if ("project".equals(context.resourceType())) {
            return (relations.contains(BusinessRelation.COLLABORATOR)
                    || relations.contains(BusinessRelation.WATCHER))
                && READ_ACTIONS.contains(context.action());
        }
        if ((relations.contains(BusinessRelation.ASSIGNEE) || relations.contains(BusinessRelation.COLLABORATOR))
            && PARTICIPANT_ACTIONS.contains(context.action())) {
            return true;
        }
        return relations.contains(BusinessRelation.ACCEPTOR)
            && Set.of("view", "read", "comment", "accept").contains(context.action());
    }

    /**
     * 判断MemberBaseline操作是否满足要求。
     *
     * @param principal 当前操作主体
     * @param context 待处理内容
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isMemberBaselineOperation(CurrentPrincipal principal, PermissionContext context) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (!principal.hasRole(PlatformRole.MEMBER)) {
            return false;
        }
        if ("conversation".equals(context.resourceType())) {
            return MEMBER_CONVERSATION_ACTIONS.contains(context.action());
        }
        if ("workspace".equals(context.resourceType())) {
            return Set.of("read", "view", "write", "delete", "export").contains(context.action());
        }
        if ("project".equals(context.resourceType())) {
            return MEMBER_PROJECT_ACTIONS.contains(context.action());
        }
        if ("knowledge_base".equals(context.resourceType())) {
            return MEMBER_KNOWLEDGE_ACTIONS.contains(context.action());
        }
        if ("report".equals(context.resourceType())) {
            return MEMBER_REPORT_ACTIONS.contains(context.action());
        }
        if ("notification".equals(context.resourceType())) {
            return MEMBER_NOTIFICATION_ACTIONS.contains(context.action());
        }
        return "task".equals(context.resourceType())
            && MEMBER_TASK_ACTIONS.contains(context.action());
    }

    /**
     * 处理{@code decision}并返回对应结果。
     *
     * @param effect {@code effect}参数
     * @param reasonCode {@code reasonCode}参数
     * @param reason {@code reason}参数
     * @param source 数据源参数
     * @param sourceReference 数据源Reference参数
     * @return 处理结果
     */
    private AuthorizationDecision decision(
        PermissionEffect effect,
        String reasonCode,
        String reason,
        PermissionSource source,
        String sourceReference
    ) {
        return new AuthorizationDecision(
            effect,
            reasonCode,
            reason,
            List.of(new DecisionEvidence(source, sourceReference, effect, reason))
        );
    }
}
