package group.aitools.nhs.platform.approval.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.approval.service.ApprovalApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台审批相关的 HTTP 接口，并负责请求校验与结果返回。
 * High-risk tool approval inbox and idempotent reviewer decisions. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/approvals")
public class PlatformApprovalController {

    private final ApprovalApplicationService approvalService;

    public PlatformApprovalController(ApprovalApplicationService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<ApprovalView>> list(
        @RequestParam(required = false)
        @Pattern(regexp = "pending|approved|rejected|revoked|expired") String status,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit
    ) {
        return R.ok(approvalService.list(status, limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param approvalId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{approvalId}")
    public R<ApprovalView> get(@PathVariable @Positive Long approvalId) {
        return R.ok(approvalService.get(approvalId));
    }

    /**
     * 处理{@code approve}并返回对应结果。
     *
     * @param approvalId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{approvalId}/approve")
    public R<ApprovalDecisionResult> approve(
        @PathVariable @Positive Long approvalId,
        @Valid @RequestBody ApprovalDecisionRequest request
    ) {
        return R.ok(approvalService.approve(approvalId, request));
    }

    /**
     * 处理{@code reject}并返回对应结果。
     *
     * @param approvalId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{approvalId}/reject")
    public R<ApprovalDecisionResult> reject(
        @PathVariable @Positive Long approvalId,
        @Valid @RequestBody ApprovalDecisionRequest request
    ) {
        return R.ok(approvalService.reject(approvalId, request));
    }
}
