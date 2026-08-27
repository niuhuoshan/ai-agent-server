package group.aitools.nhs.platform.artifact.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.artifact.service.AcceptanceApplicationService;
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
 * 提供平台验收相关的 HTTP 接口，并负责请求校验与结果返回。
 * Append-only artifact acceptance decisions and task completion transitions. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/tasks/{taskId}/runs/{runId}/acceptances")
public class PlatformAcceptanceController {

    private final AcceptanceApplicationService acceptanceService;

    public PlatformAcceptanceController(AcceptanceApplicationService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<AcceptanceView>> list(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return R.ok(acceptanceService.list(taskId, runId, limit));
    }

    /**
     * 处理{@code decide}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<AcceptanceDecisionResult> decide(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @Valid @RequestBody AcceptanceDecisionRequest request
    ) {
        return R.ok(acceptanceService.decide(taskId, runId, request));
    }
}
