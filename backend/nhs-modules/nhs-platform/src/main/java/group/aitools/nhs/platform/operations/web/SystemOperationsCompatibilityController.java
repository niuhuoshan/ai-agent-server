package group.aitools.nhs.platform.operations.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.portal.memory.PortalMemoryService;
import group.aitools.nhs.platform.operations.service.SystemHealthApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 提供系统OperationsCompatibility相关的 HTTP 接口，并负责请求校验与结果返回。
 * Compatibility probes and vector maintenance actions for the System page. */
@SaCheckLogin
@RestController
public class SystemOperationsCompatibilityController {

    private final SystemHealthApplicationService healthService;
    private final PortalMemoryService memoryService;
    private final AgentAuditEventMapper auditMapper;
    private final PlatformIdGenerator idGenerator;
    private final CurrentPrincipalProvider principalProvider;

    public SystemOperationsCompatibilityController(
        SystemHealthApplicationService healthService,
        PortalMemoryService memoryService,
        AgentAuditEventMapper auditMapper,
        PlatformIdGenerator idGenerator,
        CurrentPrincipalProvider principalProvider
    ) {
        this.healthService = healthService;
        this.memoryService = memoryService;
        this.auditMapper = auditMapper;
        this.idGenerator = idGenerator;
        this.principalProvider = principalProvider;
    }

    /**
     * 处理{@code testComponent}并返回对应结果。
     *
     * @param component {@code component}参数
     * @return 处理结果
     */
    @PostMapping({"/platform/operations/components/{component}/test", "/api/portal/system/test-connection/{component}"})
    public R<SystemComponentTestView> testComponent(@PathVariable String component) {
        long started = System.nanoTime();
        if ("global_embed".equals(component) || "embedding".equals(component)) {
            Map<String, Object> result = memoryService.testEmbedding();
            return R.ok(new SystemComponentTestView(
                component, "success", "Embedding 连通性测试成功", elapsed(started), result
            ));
        }
        SystemHealthComponentView result = healthService.testComponent(component);
        audit(component, result.status(), result.message());
        return R.ok(new SystemComponentTestView(
            component, result.status(), result.message(), result.responseTimeMs(), result.details()
        ));
    }

    /**
     * 处理{@code rebuildVectors}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping({"/platform/operations/vectors/rebuild", "/api/portal/system/redis/rebuild-vectors"})
    public R<Map<String, Object>> rebuildVectors() {
        return R.ok(memoryService.rebuildVectorIndex());
    }

    /**
     * 处理{@code elapsed}并返回对应结果。
     *
     * @param started {@code started}参数
     * @return 处理结果
     */
    private long elapsed(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    /**
     * 处理审计相关逻辑。
     *
     * @param component {@code component}参数
     * @param status 目标状态
     * @param message 待处理内容
     */
    private void audit(String component, String status, String message) {
        try {
            CurrentPrincipal principal = principalProvider.currentPrincipal();
            auditMapper.insertEvent(
                idGenerator.nextId(), actorType(principal), principal.id(),
                "test_connection", "system_component", null,
                null, "failure".equals(status) || "unavailable".equals(status) ? "failure" : "success",
                component, message, LocalDateTime.now()
            );
        } catch (RuntimeException ignored) {
            // Preserve the probe result; readiness diagnostics reports audit persistence failures.
        }
    }

    /**
     * 处理{@code actorType}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private String actorType(CurrentPrincipal principal) {
        return principal.type() == PrincipalType.SERVICE_ACCOUNT ? "service_account" : "user";
    }
}
