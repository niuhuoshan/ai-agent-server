package group.aitools.nhs.platform.web.auth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.PlatformUiPermissionService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供Nhs门户认证相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs portal identity projection backed by the NHS login and platform IAM. */
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/auth")
public class NhsPortalAuthController {

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformUiPermissionService permissionService;

    public NhsPortalAuthController(
        CurrentPrincipalProvider principalProvider,
        PlatformUiPermissionService permissionService
    ) {
        this.principalProvider = principalProvider;
        this.permissionService = permissionService;
    }

    /**
     * 处理{@code me}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", principal.id());
        data.put("user_id", principal.id());
        data.put("user_name", principal.username());
        data.put("real_name", principal.username());
        data.put("role", principal.roles().stream().anyMatch(role ->
            "platform_admin".equals(role.key()) || "superadmin".equals(role.key())
        ) ? "admin" : "user");
        data.put("status", "active");
        data.put("permissions", Map.of(
            "buttons", permissionService.buttons(principal),
            "routes", permissionService.allowedRoutes(principal)
        ));
        return Map.of("status", "success", "data", data);
    }

    /**
     * 处理{@code permissions}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/permissions")
    public R<Map<String, Object>> permissions() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return R.ok(Map.of(
            "buttons", permissionService.buttons(principal),
            "routes", permissionService.allowedRoutes(principal)
        ));
    }

    /**
 * 处理用户接口Key并返回对应结果。
 * Session authentication has already validated the caller at this point. */
    @GetMapping("/user_apikey")
    public Map<String, Object> userApiKey() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return Map.of(
            "status", "success",
            "data", Map.of("valid", true, "user_id", principal.id(), "user_name", principal.username())
        );
    }

    /**
     * 处理{@code logout}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        StpUtil.logout();
        return Map.of("status", "success", "message", "Logged out successfully");
    }
}
