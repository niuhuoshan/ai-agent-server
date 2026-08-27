package group.aitools.nhs.platform.audit.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.audit.service.AuditQueryService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提供平台审计相关的 HTTP 接口，并负责请求校验与结果返回。
 * Platform administrator's sanitized audit search endpoint. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/audit-events")
public class PlatformAuditController {

    private final AuditQueryService auditService;

    public PlatformAuditController(AuditQueryService auditService) {
        this.auditService = auditService;
    }

    /**
     * 查询{@code search}列表。
     *
     * @param actorType 业务类型
     * @param actorId 资源标识
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param decision {@code decision}参数
     * @param createdFrom {@code createdFrom}参数
     * @param createdTo {@code createdTo}参数
     * @param beforeId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<AuditEventView>> search(
        @RequestParam(required = false)
        @Pattern(regexp = "user|service_account|application|agent|system") String actorType,
        @RequestParam(required = false) @Positive Long actorId,
        @RequestParam(required = false) @Size(max = 64) String action,
        @RequestParam(required = false) @Size(max = 32) String resourceType,
        @RequestParam(required = false) @Positive Long resourceId,
        @RequestParam(required = false) @Positive Long taskId,
        @RequestParam(required = false) @Positive Long runId,
        @RequestParam(required = false)
        @Pattern(regexp = "allow|deny|approval_required|success|failure") String decision,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
        @RequestParam(required = false) @Positive Long beforeId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return R.ok(auditService.search(
            actorType, actorId, action, resourceType, resourceId, taskId, runId,
            decision, createdFrom, createdTo, beforeId, limit
        ));
    }
}
