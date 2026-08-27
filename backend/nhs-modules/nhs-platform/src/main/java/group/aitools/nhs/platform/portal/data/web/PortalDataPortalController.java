package group.aitools.nhs.platform.portal.data.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import group.aitools.nhs.platform.portal.data.service.PortalDataPortalService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Nhs-compatible data portal home endpoint. */
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/data-portal")
public class PortalDataPortalController {

    private final PortalDataPortalService service;

    public PortalDataPortalController(PortalDataPortalService service) {
        this.service = service;
    }

    @GetMapping("/home")
    public R<Map<String, Object>> home() {
        return R.ok(service.home());
    }
}
