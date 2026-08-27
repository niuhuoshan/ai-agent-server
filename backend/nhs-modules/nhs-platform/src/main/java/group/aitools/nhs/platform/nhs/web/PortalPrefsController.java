package group.aitools.nhs.platform.nhs.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import group.aitools.nhs.platform.nhs.service.PortalPrefsApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

import static group.aitools.nhs.platform.nhs.web.PortalPrefsContracts.MarkdownThemeRequest;
import static group.aitools.nhs.platform.nhs.web.PortalPrefsContracts.Preferences;
import static group.aitools.nhs.platform.nhs.web.PortalPrefsContracts.RoutingPreferenceRequest;

/**
 * 提供门户Prefs相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs-compatible user portal preference endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/portal-prefs")
public class PortalPrefsController {

    private final PortalPrefsApplicationService service;

    public PortalPrefsController(PortalPrefsApplicationService service) {
        this.service = service;
    }

    /**
     * 获取{@code get}。
     *
     * @return 处理结果
     */
    @GetMapping
    public R<Preferences> get() {
        return R.ok(service.get());
    }

    /**
     * 更新{@code update}。
     *
     * @param preferences {@code preferences}参数
     * @return 处理结果
     */
    @PutMapping
    public R<Preferences> update(@Valid @RequestBody Preferences preferences) {
        return R.ok("偏好已保存", service.update(preferences));
    }

    /**
     * 更新{@code MarkdownTheme}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/markdown-theme")
    public R<Map<String, Object>> updateMarkdownTheme(@Valid @RequestBody MarkdownThemeRequest request) {
        return R.ok("样式偏好已保存", Map.of(
            "markdown_theme", service.updateMarkdownTheme(request == null ? null : request.theme())
        ));
    }

    /**
     * 更新{@code Routing}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/routing")
    public R<Map<String, Object>> updateRouting(
        @Valid @RequestBody RoutingPreferenceRequest request
    ) {
        Preferences updated = service.updateRouting(request);
        return R.ok("路由偏好已保存", Map.of(
            "routing_mode", updated.routingMode(),
            "expert_agent_id", updated.expertAgentId(),
            "routing_configured", updated.routingConfigured()
        ));
    }
}
