package group.aitools.nhs.platform.migration.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.migration.service.LegacyExecutionArchiveQueryService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台Legacy执行Archive相关的 HTTP 接口，并负责请求校验与结果返回。
 * Read-only, administrator-authorized Nhs execution archive search. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/migration/legacy-executions")
public class PlatformLegacyExecutionArchiveController {

    private final LegacyExecutionArchiveQueryService service;

    public PlatformLegacyExecutionArchiveController(LegacyExecutionArchiveQueryService service) {
        this.service = service;
    }

    /**
     * 查询{@code search}列表。
     *
     * @param traceId 资源标识
     * @param executionId 资源标识
     * @param sourceStatus 目标状态
     * @param beforeId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<LegacyExecutionArchiveView>> search(
        @RequestParam(required = false) @Size(max = 128) String traceId,
        @RequestParam(required = false) @Size(max = 128) String executionId,
        @RequestParam(required = false) @Size(max = 32) String sourceStatus,
        @RequestParam(required = false) @Positive Long beforeId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return R.ok(service.search(traceId, executionId, sourceStatus, beforeId, limit));
    }
}
