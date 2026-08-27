package group.aitools.nhs.platform.operations.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import group.aitools.nhs.platform.operations.service.SystemHealthApplicationService;
import group.aitools.nhs.platform.operations.service.SystemDiagnosticsApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供系统健康状态相关的 HTTP 接口，并负责请求校验与结果返回。
 * Administrator-only deployment health overview. */
@SaCheckLogin
@RestController
@RequestMapping({"/platform/operations", "/api/portal/health"})
public class SystemHealthController {

    private final SystemHealthApplicationService service;
    private final SystemDiagnosticsApplicationService diagnosticsService;

    public SystemHealthController(
        SystemHealthApplicationService service,
        SystemDiagnosticsApplicationService diagnosticsService
    ) {
        this.service = service;
        this.diagnosticsService = diagnosticsService;
    }

    /**
     * 处理健康状态并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/health")
    public R<SystemHealthOverviewView> health() {
        return R.ok(service.overview());
    }

    /**
     * 处理{@code diagnostics}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/diagnostics")
    public R<SystemDiagnosticsView> diagnostics() {
        return R.ok(diagnosticsService.diagnostics());
    }

    /**
     * 处理{@code readiness}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/readiness")
    public ResponseEntity<R<SystemDiagnosticsView>> readiness() {
        SystemDiagnosticsView diagnostics = diagnosticsService.diagnostics();
        return ResponseEntity.status(diagnostics.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
            .body(R.ok(diagnostics));
    }
}
