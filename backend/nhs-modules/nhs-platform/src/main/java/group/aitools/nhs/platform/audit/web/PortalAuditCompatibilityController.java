package group.aitools.nhs.platform.audit.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.audit.service.AuditOperationsApplicationService;
import group.aitools.nhs.platform.audit.service.AuditOperationsApplicationService.AuditFilter;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 提供门户审计Compatibility相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs Portal audit paths backed by the platform's durable business audit events. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/audit")
public class PortalAuditCompatibilityController {

    private final AuditOperationsApplicationService service;

    public PortalAuditCompatibilityController(AuditOperationsApplicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code features}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/features")
    public R<List<String>> features() {
        return R.ok(service.features().actions());
    }

    /**
     * 处理{@code logs}并返回对应结果。
     *
     * @param page {@code page}参数
     * @param size 数量上限
     * @param actorType 业务类型
     * @param actorId 资源标识
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param decision {@code decision}参数
     * @param startTime {@code startTime}参数
     * @param endTime {@code endTime}参数
     * @param includeStats {@code includeStats}参数
     * @return 处理结果
     */
    @GetMapping("/logs")
    public R<AuditPageView> logs(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(name = "actor_type", required = false) String actorType,
        @RequestParam(name = "actor_id", required = false) @Positive Long actorId,
        @RequestParam(required = false) String action,
        @RequestParam(name = "resource_type", required = false) String resourceType,
        @RequestParam(name = "resource_id", required = false) @Positive Long resourceId,
        @RequestParam(name = "task_id", required = false) @Positive Long taskId,
        @RequestParam(name = "run_id", required = false) @Positive Long runId,
        @RequestParam(required = false) String decision,
        @RequestParam(name = "start_time", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
        @RequestParam(name = "end_time", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
        @RequestParam(name = "include_stats", defaultValue = "false") boolean includeStats
    ) {
        long requestedOffset = (long) (page - 1) * size;
        int offset = requestedOffset >= 10_000 ? 10_000 : (int) requestedOffset;
        int readLimit = Math.min(10_000, offset + size);
        AuditFilter filter = filter(
            actorType, actorId, action, resourceType, resourceId, taskId, runId, decision,
            startTime, endTime, readLimit
        );
        List<AuditEventView> fetched = offset >= 10_000
            ? List.of() : service.search(filter, readLimit, null);
        List<AuditEventView> items = offset >= fetched.size()
            ? List.of() : List.copyOf(fetched.subList(offset, Math.min(fetched.size(), offset + size)));
        return R.ok(new AuditPageView(
            service.count(filter), page, size, items,
            includeStats ? service.statistics(filter) : null
        ));
    }

    /**
     * 处理导出并返回对应结果。
     *
     * @param format {@code format}参数
     * @param actorType 业务类型
     * @param actorId 资源标识
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param decision {@code decision}参数
     * @param startTime {@code startTime}参数
     * @param endTime {@code endTime}参数
     * @return 处理结果
     */
    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> export(
        @RequestParam(defaultValue = "csv") String format,
        @RequestParam(name = "actor_type", required = false) String actorType,
        @RequestParam(name = "actor_id", required = false) @Positive Long actorId,
        @RequestParam(required = false) String action,
        @RequestParam(name = "resource_type", required = false) String resourceType,
        @RequestParam(name = "resource_id", required = false) @Positive Long resourceId,
        @RequestParam(required = false) String decision,
        @RequestParam(name = "start_time", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
        @RequestParam(name = "end_time", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime
    ) {
        AuditExportedFile file = service.export(
            filter(actorType, actorId, action, resourceType, resourceId, null, null, decision,
                startTime, endTime, 10_000),
            format
        );
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(file.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(MediaType.parseMediaType(file.mediaType()))
            .contentLength(file.content().length)
            .body(file.content());
    }

    /**
     * 处理{@code detail}并返回对应结果。
     *
     * @param logId 资源标识
     * @return 处理结果
     */
    @GetMapping("/logs/{logId}")
    public R<AuditEventDetailView> detail(@PathVariable @Positive Long logId) {
        return R.ok(service.detail(logId));
    }

    /**
     * 处理链路追踪并返回对应结果。
     *
     * @param traceId 资源标识
     * @return 处理结果
     */
    @GetMapping("/traces/{traceId}")
    public R<AuditTraceView> trace(@PathVariable String traceId) {
        return R.ok(service.trace(traceId));
    }

    /**
     * 处理{@code spans}并返回对应结果。
     *
     * @param traceId 资源标识
     * @return 处理结果
     */
    @GetMapping("/traces/{traceId}/spans")
    public R<AuditTraceSpansView> spans(@PathVariable String traceId) {
        return R.ok(service.spans(traceId));
    }

    /**
     * 处理{@code filter}并返回对应结果。
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
     * @param limit 数量上限
     * @return 处理结果
     */
    private AuditFilter filter(
        String actorType,
        Long actorId,
        String action,
        String resourceType,
        Long resourceId,
        Long taskId,
        Long runId,
        String decision,
        LocalDateTime createdFrom,
        LocalDateTime createdTo,
        int limit
    ) {
        return new AuditFilter(
            actorType, actorId, action, resourceType, resourceId, taskId, runId,
            decision, createdFrom, createdTo, limit
        );
    }
}
