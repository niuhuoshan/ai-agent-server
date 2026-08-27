package group.aitools.nhs.platform.operations.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import group.aitools.nhs.platform.operations.service.LogMaintenanceApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供{@code LogMaintenance}相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs-compatible log retention with PostgreSQL-native guarded maintenance. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/operations/logs", "/api/portal/system/logs"})
public class LogMaintenanceController {

    private final LogMaintenanceApplicationService service;

    public LogMaintenanceController(LogMaintenanceApplicationService service) {
        this.service = service;
    }

    /**
     * 处理配置并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/config")
    public R<LogRetentionConfigView> configuration() {
        return R.ok(service.configuration());
    }

    /**
     * 更新配置。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/config")
    public R<LogRetentionConfigView> updateConfiguration(
        @Valid @RequestBody UpdateLogRetentionConfigRequest request
    ) {
        return R.ok(service.updateConfiguration(request));
    }

    /**
     * 处理put配置并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/config")
    public R<LogRetentionConfigView> putConfiguration(
        @Valid @RequestBody UpdateLogRetentionConfigRequest request
    ) {
        return R.ok(service.updateConfiguration(request));
    }

    /**
     * 处理{@code partitions}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/partitions")
    public R<LogPartitionStatusView> partitions() {
        return R.ok(service.partitions());
    }

    /**
     * 处理{@code previewCleanup}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping("/cleanup/preview")
    public R<LogCleanupPreviewView> previewCleanup() {
        return R.ok(service.previewCleanup());
    }

    /**
     * 处理{@code cleanup}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/cleanup")
    public R<LogCleanupResultView> cleanup(@Valid @RequestBody LogCleanupRequest request) {
        return R.ok(service.cleanup(request));
    }

    /**
     * 处理{@code recentRuns}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/maintenance-runs")
    public R<List<LogMaintenanceRunView>> recentRuns(
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return R.ok(service.recentRuns(limit));
    }
}
