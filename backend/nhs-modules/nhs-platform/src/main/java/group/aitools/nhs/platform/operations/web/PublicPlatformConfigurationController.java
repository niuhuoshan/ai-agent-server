package group.aitools.nhs.platform.operations.web;

import cn.dev33.satoken.annotation.SaIgnore;
import group.aitools.nhs.platform.operations.service.PlatformConfigurationApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供Public平台配置相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@SaIgnore
@RestController
@RequestMapping({"/open/platform/configuration", "/api/portal/auth/config/public"})
public class PublicPlatformConfigurationController {

    private final PlatformConfigurationApplicationService service;

    /**
     * 创建 {@code PublicPlatformConfigurationController} 实例并初始化所需依赖。
     *
     * @param service {@code service}参数
     */
    public PublicPlatformConfigurationController(PlatformConfigurationApplicationService service) {
        this.service = service;
    }

    /**
     * 处理当前并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping
    public R<PublicPlatformConfigurationView> current() {
        return R.ok(service.publicConfiguration());
    }
}
