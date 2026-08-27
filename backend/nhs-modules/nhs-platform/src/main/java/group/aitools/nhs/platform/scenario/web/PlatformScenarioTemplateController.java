package group.aitools.nhs.platform.scenario.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.scenario.service.ScenarioTemplateApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台Scenario模板相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs scenario-template catalog and delivery API. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/scenario-templates", "/api/portal/scenario-templates"})
public class PlatformScenarioTemplateController {

    private final ScenarioTemplateApplicationService service;

    public PlatformScenarioTemplateController(ScenarioTemplateApplicationService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @return 处理结果
     */
    @GetMapping({"", "/"})
    public R<List<ScenarioTemplateViews.Summary>> list() {
        return R.ok(service.listTemplates());
    }

    /**
     * 处理{@code instances}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/instances")
    public R<List<ScenarioTemplateViews.Instance>> instances(
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.listInstances(limit));
    }

    /**
     * 处理{@code instance}并返回对应结果。
     *
     * @param instanceId 资源标识
     * @return 处理结果
     */
    @GetMapping("/instances/{instanceId}")
    public R<ScenarioTemplateViews.Instance> instance(@PathVariable @Positive Long instanceId) {
        return R.ok(service.getInstance(instanceId));
    }

    /**
     * 获取{@code get}。
     *
     * @param templateId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{templateId}")
    public R<ScenarioTemplateViews.Detail> get(@PathVariable @Size(max = 128) String templateId) {
        return R.ok(service.getTemplate(templateId));
    }

    /**
     * 处理{@code options}并返回对应结果。
     *
     * @param templateId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{templateId}/resource-options")
    public R<ScenarioTemplateViews.ResourceOptions> options(@PathVariable @Size(max = 128) String templateId) {
        return R.ok(service.resourceOptions(templateId));
    }

    /**
     * 处理{@code precheck}并返回对应结果。
     *
     * @param templateId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{templateId}/precheck")
    public R<ScenarioTemplateViews.Precheck> precheck(
        @PathVariable @Size(max = 128) String templateId,
        @Valid @RequestBody ScenarioTemplateRequest request
    ) {
        return R.ok(service.precheck(templateId, request));
    }

    /**
     * 处理{@code install}并返回对应结果。
     *
     * @param templateId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{templateId}/install")
    public R<ScenarioTemplateViews.Install> install(
        @PathVariable @Size(max = 128) String templateId,
        @Valid @RequestBody ScenarioTemplateRequest request
    ) {
        return R.ok(service.install(templateId, request));
    }

    /**
     * 处理{@code uninstall}并返回对应结果。
     *
     * @param instanceId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/instances/{instanceId}/uninstall")
    public R<ScenarioTemplateViews.Uninstall> uninstall(
        @PathVariable @Positive Long instanceId,
        @Valid @RequestBody ScenarioTemplateUninstallRequest request
    ) {
        return R.ok(service.uninstall(instanceId, request));
    }

    /**
 * 删除{@code Instance}。
 * DELETE is kept as a REST-friendly alias for API clients that do not expose the action name. */
    @DeleteMapping("/instances/{instanceId}")
    public R<ScenarioTemplateViews.Uninstall> deleteInstance(
        @PathVariable @Positive Long instanceId,
        @Valid @RequestBody ScenarioTemplateUninstallRequest request
    ) {
        return R.ok(service.uninstall(instanceId, request));
    }
}
