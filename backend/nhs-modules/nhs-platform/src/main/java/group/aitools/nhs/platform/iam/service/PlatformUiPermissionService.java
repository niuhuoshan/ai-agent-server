package group.aitools.nhs.platform.iam.service;

import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责平台Ui权限相关的业务编排与领域规则处理。
 *
 * Projects the effective platform authorization into SoybeanAdmin routes and buttons.
 *
 * <p>This service deliberately calls {@link AuthorizationService} directly. UI discovery
 * happens on every login and route refresh, so it must not go through
 * {@link AuthorizationEnforcer}, which would create an audit event for every menu item.</p>
 */
@Service
public final class PlatformUiPermissionService {

    private static final Set<BusinessRelation> PERSONAL_OWNER = Set.of(BusinessRelation.OWNER);

    private static final Map<String, List<Capability>> ROUTE_CAPABILITIES = routeCapabilities();
    private static final Map<String, List<Capability>> BUTTON_CAPABILITIES = buttonCapabilities();

    private final AuthorizationService authorizationService;

    public PlatformUiPermissionService(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
 * 处理{@code allowedRoutes}并返回对应结果。
 *
     * Returns the menu route names the current human principal can actually use.
     * Service accounts intentionally receive no UI routes.
     */
    public Set<String> allowedRoutes(CurrentPrincipal principal) {
        if (principal == null || !principal.isHuman()) {
            return Set.of();
        }
        return allowedNames(principal, ROUTE_CAPABILITIES);
    }

    /**
 * 处理{@code buttons}并返回对应结果。
 *
     * Returns stable resource/action button codes for the current principal.
     * General codes use the same {@code resourceType:action} vocabulary as API scopes.
     * Resource-center controls use a {@code resource:} prefix so tab/action permissions
     * cannot collide with controls on other product surfaces.
     */
    public List<String> buttons(CurrentPrincipal principal) {
        if (principal == null || !principal.isHuman()) {
            return List.of();
        }
        return List.copyOf(allowedNames(principal, BUTTON_CAPABILITIES));
    }

    /**
     * 判断{@code RouteAllowed}是否满足要求。
     *
     * @param principal 当前操作主体
     * @param routeName 名称
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean isRouteAllowed(CurrentPrincipal principal, String routeName) {
        if (principal == null || routeName == null || !principal.isHuman()) {
            return false;
        }
        List<Capability> capabilities = ROUTE_CAPABILITIES.get(routeName);
        return capabilities != null && anyAllowed(principal, capabilities);
    }

    /**
     * 处理{@code allowedNames}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param capabilities {@code capabilities}参数
     * @return 符合条件的数据集合
     */
    private Set<String> allowedNames(
        CurrentPrincipal principal,
        Map<String, List<Capability>> capabilities
    ) {
        Set<String> result = new LinkedHashSet<>();
        Map<PermissionKey, AuthorizationDecision> decisions = new LinkedHashMap<>();
        for (Map.Entry<String, List<Capability>> entry : capabilities.entrySet()) {
            if (anyAllowed(principal, entry.getValue(), decisions)) {
                result.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 处理{@code anyAllowed}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param capabilities {@code capabilities}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean anyAllowed(CurrentPrincipal principal, List<Capability> capabilities) {
        return anyAllowed(principal, capabilities, new LinkedHashMap<>());
    }

    /**
     * 处理{@code anyAllowed}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param capabilities {@code capabilities}参数
     * @param decisions {@code decisions}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean anyAllowed(
        CurrentPrincipal principal,
        List<Capability> capabilities,
        Map<PermissionKey, AuthorizationDecision> decisions
    ) {
        for (Capability capability : capabilities) {
            PermissionContext context = capability.context(principal);
            PermissionKey key = PermissionKey.from(context);
            AuthorizationDecision decision = decisions.computeIfAbsent(
                key, ignored -> authorizationService.authorize(principal, context)
            );
            if (decision.allowed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 处理{@code routeCapabilities}并返回对应结果。
     *
     * @return 处理结果
     */
    private static Map<String, List<Capability>> routeCapabilities() {
        Map<String, List<Capability>> result = new LinkedHashMap<>();
        result.put("client", List.of(capability("conversation", "list")));
        result.put("home", List.of(capability("workspace", "view")));
        result.put("dashboard", List.of(capability("workspace", "view")));
        result.put("workspace", List.of(capability("conversation", "list")));
        result.put("task-center", List.of(capability("task", "list")));
        result.put("project-center", List.of(capability("project", "list")));
        result.put("agent-center", List.of(capability("agent", "list")));
        result.put("agent-debug", List.of(
            capability("agent", "list"), capability("task", "create")
        ));
        result.put("scenario-templates", List.of(capability("agent", "list")));
        result.put("knowledge", List.of(capability("knowledge_base", "list")));
        result.put("data-source", List.of(capability("data_source", "list")));
        result.put("automation", List.of(capability("iam", "view")));
        result.put("risk-control", List.of(
            capability("notification", "list"),
            capability("approval", "list"),
            capability("audit", "list")
        ));
        result.put("saved-reports", List.of(capability("report", "list")));
        result.put("data-portal", List.of(capability("report", "list")));
        result.put("chatbi", List.of(
            capability("report", "list"), capability("data_source", "list")
        ));
        result.put("prompt-studio", List.of(capability("agent", "list")));
        result.put("slash-commands", List.of(capability("conversation", "list")));
        result.put("memory", List.of(personal("memory", "list")));
        result.put("examples", List.of(capability("report", "list")));
        result.put("personal-center", List.of(capability("workspace", "view")));
        result.put("token-stats", List.of(capability("conversation", "list")));
        result.put("resource-center", List.of(
            personal("skill", "list"),
            personal("memory", "list"),
            capability("model", "list"),
            capability("connector", "list"),
            capability("tool", "list")
        ));
        result.put("open-api", List.of(capability("iam", "manage")));
        result.put("widget-debugger", List.of(
            capability("agent", "list"), capability("iam", "manage")
        ));
        result.put("system", List.of(capability("iam", "manage")));
        return Collections.unmodifiableMap(result);
    }

    /**
     * 处理{@code buttonCapabilities}并返回对应结果。
     *
     * @return 处理结果
     */
    private static Map<String, List<Capability>> buttonCapabilities() {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, List<Capability>> result = new LinkedHashMap<>();
        result.put("workspace:view", List.of(capability("workspace", "view")));
        result.put("conversation:list", List.of(capability("conversation", "list")));
        result.put("conversation:create", List.of(capability("conversation", "create")));
        result.put("conversation:stop", List.of(capability("conversation", "stop")));
        result.put("conversation:export", List.of(capability("conversation", "export")));
        result.put("task:list", List.of(capability("task", "list")));
        result.put("task:create", List.of(capability("task", "create")));
        result.put("project:list", List.of(capability("project", "list")));
        result.put("project:create", List.of(capability("project", "create")));
        result.put("knowledge_base:list", List.of(capability("knowledge_base", "list")));
        result.put("knowledge_base:create", List.of(capability("knowledge_base", "create")));
        result.put("report:list", List.of(capability("report", "list")));
        result.put("report:create", List.of(capability("report", "create")));
        result.put("notification:list", List.of(capability("notification", "list")));
        result.put("skill:list", List.of(personal("skill", "list")));
        result.put("skill:create", List.of(personal("skill", "create")));
        result.put("skill:update", List.of(personal("skill", "update")));
        result.put("skill:delete", List.of(personal("skill", "delete")));
        result.put("skill:publish", List.of(personal("skill", "publish")));
        result.put("memory:list", List.of(personal("memory", "list")));
        result.put("memory:create", List.of(personal("memory", "create")));
        result.put("memory:update", List.of(personal("memory", "update")));
        result.put("memory:delete", List.of(personal("memory", "delete")));
        result.put("memory:review", List.of(personal("memory", "review")));
        result.put("agent:list", List.of(capability("agent", "list")));
        result.put("agent:create", List.of(capability("agent", "create")));
        result.put("agent:update", List.of(capability("agent", "update")));
        result.put("agent:delete", List.of(capability("agent", "delete")));
        result.put("agent-debug:list", List.of(capability("agent", "list")));
        result.put("agent-debug:run", List.of(capability("task", "create")));
        result.put("model:list", List.of(capability("model", "list")));
        result.put("model:create", List.of(capability("model", "create")));
        result.put("model:update", List.of(capability("model", "update")));
        result.put("model:delete", List.of(capability("model", "delete")));
        result.put("model:operate", List.of(capability("model", "operate")));
        result.put("connector:list", List.of(capability("connector", "list")));
        result.put("connector:create", List.of(capability("connector", "create")));
        result.put("connector:update", List.of(capability("connector", "update")));
        result.put("connector:delete", List.of(capability("connector", "delete")));
        result.put("connector:operate", List.of(capability("connector", "operate")));
        result.put("tool:list", List.of(capability("tool", "list")));
        result.put("tool:create", List.of(capability("tool", "create")));
        result.put("tool:update", List.of(capability("tool", "update")));
        result.put("tool:delete", List.of(capability("tool", "delete")));
        result.put("resource:model:list", List.of(capability("model", "list")));
        result.put("resource:model:create", List.of(capability("model", "create")));
        result.put("resource:model:edit", List.of(capability("model", "update")));
        result.put("resource:model:delete", List.of(capability("model", "delete")));
        result.put("resource:model:operate", List.of(capability("model", "operate")));
        result.put("resource:connector:list", List.of(capability("connector", "list")));
        result.put("resource:connector:create", List.of(capability("connector", "create")));
        result.put("resource:connector:edit", List.of(capability("connector", "update")));
        result.put("resource:connector:delete", List.of(capability("connector", "delete")));
        result.put("resource:connector:operate", List.of(capability("connector", "operate")));
        result.put("resource:tool:list", List.of(capability("tool", "list")));
        result.put("resource:tool:create", List.of(capability("tool", "create")));
        result.put("resource:tool:edit", List.of(capability("tool", "update")));
        result.put("resource:tool:delete", List.of(capability("tool", "delete")));
        result.put("resource:tool:operate", List.of(capability("tool", "update")));
        result.put("resource:skill:list", List.of(personal("skill", "list")));
        result.put("resource:skill:create", List.of(personal("skill", "create")));
        result.put("resource:skill:edit", List.of(personal("skill", "update")));
        result.put("resource:skill:delete", List.of(personal("skill", "delete")));
        result.put("resource:skill:operate", List.of(personal("skill", "update")));
        result.put("resource:skill:publish", List.of(personal("skill", "publish")));
        result.put("resource:skill:archive", List.of(personal("skill", "archive")));
        result.put("resource:memory:list", List.of(personal("memory", "list")));
        result.put("resource:memory:create", List.of(personal("memory", "create")));
        result.put("resource:memory:edit", List.of(personal("memory", "update")));
        result.put("resource:memory:delete", List.of(personal("memory", "delete")));
        result.put("resource:memory:operate", List.of(capability("memory", "manage")));
        result.put("approval:list", List.of(capability("approval", "list")));
        result.put("approval:approve", List.of(capability("approval", "approve")));
        result.put("approval:reject", List.of(capability("approval", "reject")));
        result.put("data_source:list", List.of(capability("data_source", "list")));
        result.put("data_source:create", List.of(capability("data_source", "create")));
        result.put("data_source:update", List.of(capability("data_source", "update")));
        result.put("data_source:delete", List.of(capability("data_source", "delete")));
        result.put("data_source:operate", List.of(capability("data_source", "operate")));
        result.put("iam:view", List.of(capability("iam", "view")));
        result.put("iam:manage", List.of(capability("iam", "manage")));
        result.put("audit:list", List.of(capability("audit", "list")));
        result.put("audit:export", List.of(capability("audit", "export")));
        return Collections.unmodifiableMap(result);
    }

    /**
     * 处理{@code capability}并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param action {@code action}参数
     * @return 处理结果
     */
    private static Capability capability(String resourceType, String action) {
        return new Capability(resourceType, null, null, action, Set.of());
    }

    /**
     * 处理{@code personal}并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param action {@code action}参数
     * @return 处理结果
     */
    private static Capability personal(String resourceType, String action) {
        return new Capability(resourceType, null, null, action, PERSONAL_OWNER);
    }

    /**
     * 封装{@code Capability}相关的不可变数据。
     */
    private record Capability(
        String resourceType,
        Long resourceId,
        String resourceKey,
        String action,
        Set<BusinessRelation> relations
    ) {

        /**
         * 处理上下文并返回对应结果。
         *
         * @param principal 当前操作主体
         * @return 处理结果
         */
        private PermissionContext context(CurrentPrincipal principal) {
            Long id = resourceId;
            if ("memory".equals(resourceType)) {
                id = principal.id();
            }
            return new PermissionContext(
                resourceType, id, resourceKey, action, ResourceState.ACTIVE, true, relations, null
            );
        }
    }

    /**
     * 封装权限Key相关的不可变数据。
     */
    private record PermissionKey(
        String resourceType,
        Long resourceId,
        String resourceKey,
        String action,
        Set<BusinessRelation> relations
    ) {

        /**
         * 处理{@code from}并返回对应结果。
         *
         * @param context 待处理内容
         * @return 处理结果
         */
        private static PermissionKey from(PermissionContext context) {
            return new PermissionKey(
                context.resourceType(), context.resourceId(), context.resourceKey(),
                context.action(), context.relations()
            );
        }
    }
}
