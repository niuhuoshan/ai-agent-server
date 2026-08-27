package group.aitools.nhs.platform.operations.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import group.aitools.nhs.platform.operations.service.PlatformConfigurationApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台配置相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/operations/configuration")
public class PlatformConfigurationController {

    private final PlatformConfigurationApplicationService service;

    /**
     * 创建 {@code PlatformConfigurationController} 实例并初始化所需依赖。
     *
     * @param service {@code service}参数
     */
    public PlatformConfigurationController(PlatformConfigurationApplicationService service) {
        this.service = service;
    }

    /**
     * 处理当前并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping
    public R<PlatformConfigurationView> current() {
        return R.ok(service.current());
    }

    /**
     * 更新{@code update}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping
    public R<PlatformConfigurationView> update(
        @Valid @RequestBody UpdatePlatformConfigurationRequest request
    ) {
        return R.ok(service.update(request));
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/history")
    public R<List<PlatformConfigurationHistoryView>> history(
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return R.ok(service.history(limit));
    }
}
