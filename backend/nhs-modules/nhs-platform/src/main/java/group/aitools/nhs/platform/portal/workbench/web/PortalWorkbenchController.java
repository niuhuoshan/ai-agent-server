package group.aitools.nhs.platform.portal.workbench.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import group.aitools.nhs.platform.portal.workbench.service.PortalWorkbenchService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供门户Workbench相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs-compatible personal workbench home endpoint. */
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/workbench")
public class PortalWorkbenchController {

    private final PortalWorkbenchService service;

    public PortalWorkbenchController(PortalWorkbenchService service) {
        this.service = service;
    }

    /**
     * 处理{@code home}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/home")
    public R<Map<String, Object>> home() {
        return R.ok(service.home());
    }
}
