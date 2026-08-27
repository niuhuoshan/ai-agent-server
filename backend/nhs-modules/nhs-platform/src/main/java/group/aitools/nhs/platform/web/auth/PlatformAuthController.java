package group.aitools.nhs.platform.web.auth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.PlatformUiPermissionService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * 提供平台认证相关的 HTTP 接口，并负责请求校验与结果返回。
 * SoybeanAdmin-compatible identity endpoint backed by the platform principal. */
@RestController
@RequestMapping("/auth")
public class PlatformAuthController {

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformUiPermissionService uiPermissionService;

    public PlatformAuthController(
        CurrentPrincipalProvider principalProvider,
        PlatformUiPermissionService uiPermissionService
    ) {
        this.principalProvider = principalProvider;
        this.uiPermissionService = uiPermissionService;
    }

    /**
     * 获取用户Info。
     *
     * @return 处理结果
     */
    @SaCheckLogin
    @GetMapping("/getUserInfo")
    public R<PlatformUserInfo> getUserInfo() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        List<String> roles = principal.roles().stream()
            .map(role -> role.key())
            .sorted(Comparator.naturalOrder())
            .toList();
        return R.ok(new PlatformUserInfo(
            principal.id().toString(),
            principal.username(),
            roles,
            uiPermissionService.buttons(principal)
        ));
    }
}
