package group.aitools.nhs.platform.automation.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.automation.service.AutomationApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台自动化相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/automation/triggers")
public class PlatformAutomationController {

    private final AutomationApplicationService service;

    /**
     * 创建 {@code PlatformAutomationController} 实例并初始化所需依赖。
     *
     * @param service {@code service}参数
     */
    public PlatformAutomationController(AutomationApplicationService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<AutomationTriggerView>> list(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.list(status, limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param triggerId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{triggerId}")
    public R<AutomationTriggerView> get(@PathVariable @Positive Long triggerId) {
        return R.ok(service.get(triggerId));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<AutomationTriggerView> create(
        @Valid @RequestBody CreateAutomationTriggerRequest request
    ) {
        return R.ok(service.create(request));
    }

    /**
     * 更新{@code update}。
     *
     * @param triggerId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{triggerId}")
    public R<AutomationTriggerView> update(
        @PathVariable @Positive Long triggerId,
        @Valid @RequestBody UpdateAutomationTriggerRequest request
    ) {
        return R.ok(service.update(triggerId, request));
    }

    /**
     * 处理{@code fire}并返回对应结果。
     *
     * @param triggerId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{triggerId}/fire")
    public R<AutomationFireView> fire(
        @PathVariable @Positive Long triggerId,
        @Valid @RequestBody ManualAutomationFireRequest request
    ) {
        return R.ok(service.manualFire(triggerId, request));
    }
}
