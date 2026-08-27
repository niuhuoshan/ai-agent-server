package group.aitools.nhs.platform.risk.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.risk.service.RiskPolicyApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台风险策略相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/risk-policies")
public class PlatformRiskPolicyController {

    private final RiskPolicyApplicationService service;

    /**
     * 创建 {@code PlatformRiskPolicyController} 实例并初始化所需依赖。
     *
     * @param service {@code service}参数
     */
    public PlatformRiskPolicyController(RiskPolicyApplicationService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param resourceType 业务类型
     * @param riskLevel 风险Level参数
     * @param status 目标状态
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<RiskPolicyView>> list(
        @RequestParam(required = false)
        @Pattern(regexp = "[a-z][a-z0-9_]{0,63}") String resourceType,
        @RequestParam(required = false) @Pattern(regexp = "R0|R1|R2|R3") String riskLevel,
        @RequestParam(required = false) @Pattern(regexp = "active|disabled") String status,
        @RequestParam(required = false) @Size(max = 128) String search,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.list(resourceType, riskLevel, status, search, limit));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<RiskPolicyView> create(@Valid @RequestBody SaveRiskPolicyRequest request) {
        return R.ok(service.create(request));
    }

    /**
     * 更新{@code update}。
     *
     * @param policyId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{policyId}")
    public R<RiskPolicyView> update(
        @PathVariable @Positive Long policyId,
        @Valid @RequestBody SaveRiskPolicyRequest request
    ) {
        return R.ok(service.update(policyId, request));
    }

    /**
     * 更新{@code Status}。
     *
     * @param policyId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/{policyId}/status")
    public R<RiskPolicyView> updateStatus(
        @PathVariable @Positive Long policyId,
        @Valid @RequestBody UpdateRiskPolicyStatusRequest request
    ) {
        return R.ok(service.updateStatus(policyId, request.status()));
    }

    /**
     * 删除{@code delete}。
     *
     * @param policyId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{policyId}")
    public R<Void> delete(@PathVariable @Positive Long policyId) {
        service.delete(policyId);
        return R.ok();
    }
}
