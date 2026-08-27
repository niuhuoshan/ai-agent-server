package group.aitools.nhs.platform.web.route;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaIgnore;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.PlatformUiPermissionService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 提供平台Route相关的 HTTP 接口，并负责请求校验与结果返回。
 * Minimal dynamic route contract for the first platform slice. */
@RestController
@RequestMapping("/route")
public class PlatformRouteController {

    private static final List<PlatformRoute> CONSTANT_ROUTES = List.of(
        route("403", "/403", "layout.blank$view.403", meta("403", null, null, null, true, true)),
        route("404", "/404", "layout.blank$view.404", meta("404", null, null, null, true, true)),
        route("500", "/500", "layout.blank$view.500", meta("500", null, null, null, true, true)),
        route("iframe-page", "/iframe-page/:url", "layout.base$view.iframe-page",
            meta("iframe-page", null, null, null, true, true)),
        route("login", "/login/:module(pwd-login|code-login|register|reset-pwd|bind-wechat)?",
            "layout.blank$view.login", meta("login", null, null, null, true, true))
    );
    private static final List<PlatformRoute> HUMAN_ROUTES = List.of(
        route("home", "/home", "layout.base$view.home",
            meta("home", "route.home", "lucide:house", 0, false, false)),
        route("dashboard", "/dashboard", "layout.base$view.dashboard",
            meta("dashboard", "route.dashboard", "lucide:layout-dashboard", 1, false, false)),
        route("workspace", "/workspace", "layout.base$view.workspace",
            meta("workspace", "route.workspace", "lucide:message-square", 2, false, true)),
        route("task-center", "/task-center", "layout.base$view.task-center",
            meta("task-center", "route.task-center", "lucide:list-checks", 3, false, false)),
        route("project-center", "/project-center", "layout.base$view.project-center",
            meta("project-center", "route.project-center", "lucide:folders", 4, false, false)),
        route("agent-center", "/agent-center", "layout.base$view.agent-center",
            meta("agent-center", "route.agent-center", "lucide:bot", 4, false, false)),
        route("agent-debug", "/agent-debug", "layout.base$view.agent-debug",
            meta("agent-debug", "route.agent-debug", "lucide:bug-play", 5, false, false)),
        route("scenario-templates", "/scenario-templates", "layout.base$view.scenario-templates",
            meta("scenario-templates", "route.scenario-templates", "lucide:package-check", 4, false, false)),
        route("knowledge", "/knowledge", "layout.base$view.knowledge",
            meta("knowledge", "route.knowledge", "lucide:library", 5, false, false)),
        route("data-source", "/data-source", "layout.base$view.data-source",
            meta("data-source", "route.data-source", "lucide:database", 6, false, false)),
        route("automation", "/automation", "layout.base$view.automation",
            meta("automation", "route.automation", "lucide:workflow", 7, false, false)),
        route("risk-control", "/risk-control", "layout.base$view.risk-control",
            meta("risk-control", "route.risk-control", "lucide:shield-check", 8, false, false)),
        route("saved-reports", "/saved-reports", "layout.base$view.saved-reports",
            meta("saved-reports", "route.saved-reports", "lucide:file-chart-column", 9, false, false)),
        route("data-portal", "/data-portal", "layout.base$view.data-portal",
            meta("data-portal", "route.data-portal", "lucide:chart-no-axes-combined", 10, false, false)),
        route("chatbi", "/chatbi", "layout.base$view.chatbi",
            meta("chatbi", "route.chatbi", "lucide:chart-spline", 11, false, false)),
        route("prompt-studio", "/prompt-studio", "layout.base$view.prompt-studio",
            meta("prompt-studio", "route.prompt-studio", "lucide:pen-line", 12, false, false)),
        route("slash-commands", "/slash-commands", "layout.base$view.slash-commands",
            meta("slash-commands", "route.slash-commands", "lucide:command", 13, false, false)),
        route("memory", "/memory", "layout.base$view.memory",
            meta("memory", "route.memory", "lucide:brain", 14, false, false)),
        route("examples", "/examples", "layout.base$view.examples",
            meta("examples", "route.examples", "lucide:book-open-check", 15, false, false)),
        route("personal-center", "/personal-center", "layout.base$view.personal-center",
            meta("personal-center", "route.personal-center", "lucide:user-round", 16, false, true)),
        route("token-stats", "/token-stats", "layout.base$view.token-stats",
            meta("token-stats", "route.token-stats", "lucide:calculator", 17, false, false))
    );
    private static final List<PlatformRoute> CLIENT_ROUTES = List.of(
        route("client", "/app", "layout.client$view.client",
            meta("client", "route.client", "lucide:message-square", 0, false, true))
    );
    private static final List<PlatformRoute> ADMIN_ROUTES = List.of(
        route("resource-center", "/resource-center", "layout.base$view.resource-center",
            meta("resource-center", "route.resource-center", "lucide:blocks", 10, false, false)),
        route("open-api", "/open-api", "layout.base$view.open-api",
            meta("open-api", "route.open-api", "lucide:waypoints", 11, false, false)),
        route("widget-debugger", "/client/debug", "layout.base$view.widget-debugger",
            meta("widget-debugger", "route.widget-debugger", "lucide:scan-search", 12, false, false)),
        route("system", "/system", "layout.base$view.system",
            meta("system", "route.system", "lucide:settings", 13, false, false))
    );
    private static final List<PlatformRoute> ALL_USER_ROUTES = allUserRoutes();

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformUiPermissionService uiPermissionService;

    /**
     * 创建 {@code PlatformRouteController} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param uiPermissionService ui权限Service参数
     */
    public PlatformRouteController(
        CurrentPrincipalProvider principalProvider,
        PlatformUiPermissionService uiPermissionService
    ) {
        this.principalProvider = principalProvider;
        this.uiPermissionService = uiPermissionService;
    }

    /**
     * 获取{@code ConstantRoutes}。
     *
     * @return 处理结果
     */
    @SaIgnore
    @GetMapping("/getConstantRoutes")
    public R<List<PlatformRoute>> getConstantRoutes() {
        return R.ok(CONSTANT_ROUTES);
    }

    /**
     * 获取用户Routes。
     *
     * @return 处理结果
     */
    @SaCheckLogin
    @GetMapping("/getUserRoutes")
    public R<PlatformUserRoutes> getUserRoutes() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            return R.ok(new PlatformUserRoutes(List.of(), "403"));
        }
        Set<String> allowedRouteNames = uiPermissionService.allowedRoutes(principal);
        List<PlatformRoute> routes = ALL_USER_ROUTES.stream()
            .filter(route -> allowedRouteNames.contains(route.name()))
            .toList();
        return R.ok(new PlatformUserRoutes(routes, homeRoute(routes, allowedRouteNames)));
    }

    /**
     * 判断{@code RouteExist}是否满足要求。
     *
     * @param routeName 名称
     * @return 处理结果
     */
    @SaCheckLogin
    @GetMapping("/isRouteExist")
    public R<Boolean> isRouteExist(@RequestParam String routeName) {
        boolean exists = CONSTANT_ROUTES.stream().anyMatch(route -> route.name().equals(routeName))
            || ALL_USER_ROUTES.stream().anyMatch(route -> route.name().equals(routeName));
        return R.ok(exists);
    }

    /**
     * 处理all用户Routes并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    private static List<PlatformRoute> allUserRoutes() {
        List<PlatformRoute> routes = new ArrayList<>(CLIENT_ROUTES);
        routes.addAll(HUMAN_ROUTES);
        routes.addAll(ADMIN_ROUTES);
        return List.copyOf(routes);
    }

    /**
     * 处理{@code homeRoute}并返回对应结果。
     *
     * @param routes {@code routes}参数
     * @param allowedRouteNames 名称
     * @return 处理结果
     */
    private static String homeRoute(List<PlatformRoute> routes, Set<String> allowedRouteNames) {
        if (allowedRouteNames.contains("client")) {
            return "client";
        }
        if (allowedRouteNames.contains("home")) {
            return "home";
        }
        return routes.isEmpty() ? "403" : routes.getFirst().name();
    }

    /**
     * 处理{@code route}并返回对应结果。
     *
     * @param name 名称
     * @param path {@code path}参数
     * @param component {@code component}参数
     * @param meta {@code meta}参数
     * @return 处理结果
     */
    private static PlatformRoute route(String name, String path, String component, PlatformRouteMeta meta) {
        return new PlatformRoute(name, name, path, component, false, meta, List.of());
    }

    /**
     * 处理{@code meta}并返回对应结果。
     *
     * @param title {@code title}参数
     * @param i18nKey {@code i18nKey}参数
     * @param icon {@code icon}参数
     * @param order {@code order}参数
     * @param constant {@code constant}参数
     * @param hideInMenu {@code hideInMenu}参数
     * @return 处理结果
     */
    private static PlatformRouteMeta meta(
        String title,
        String i18nKey,
        String icon,
        Integer order,
        Boolean constant,
        Boolean hideInMenu
    ) {
        return new PlatformRouteMeta(title, i18nKey, icon, order, constant, hideInMenu);
    }
}
