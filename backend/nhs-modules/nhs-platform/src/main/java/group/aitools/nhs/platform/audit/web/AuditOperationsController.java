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
 * 提供审计Operations相关的 HTTP 接口，并负责请求校验与结果返回。
 * Deep administrator audit operations kept separate from the streaming/chat controllers. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/audit-events")
public class AuditOperationsController {

    private final AuditOperationsApplicationService service;

    public AuditOperationsController(AuditOperationsApplicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code features}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/features")
    public R<AuditFeatureView> features() {
        return R.ok(service.features());
    }

    /**
     * 处理统计并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @GetMapping("/statistics")
    public R<AuditStatisticsView> statistics(AuditRequest request) {
        return R.ok(service.statistics(request.filter(100)));
    }

    /**
     * 处理导出并返回对应结果。
     *
     * @param request 请求参数
     * @param format {@code format}参数
     * @return 处理结果
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(AuditRequest request, @RequestParam(defaultValue = "csv") String format) {
        AuditExportedFile file = service.export(request.filter(100), format);
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
     * @param eventId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{eventId}")
    public R<AuditEventDetailView> detail(@PathVariable @Positive Long eventId) {
        return R.ok(service.detail(eventId));
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
 * 封装审计操作的请求参数。
 * Query object shared by detail-independent endpoints; Spring binds only bounded fields. */
    public static class AuditRequest {
        private String actorType;
        private Long actorId;
        private String action;
        private String resourceType;
        private Long resourceId;
        private Long taskId;
        private Long runId;
        private String decision;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime createdFrom;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime createdTo;
        private int limit = 50;

        public AuditFilter filter(int fallbackLimit) {
            return new AuditFilter(actorType, actorId, action, resourceType, resourceId, taskId, runId,
                decision, createdFrom, createdTo, limit <= 0 ? fallbackLimit : limit);
        }

        /**
         * 获取{@code ActorType}。
         *
         * @return 处理结果
         */
        public String getActorType() { return actorType; }
        /**
         * 设置{@code ActorType}。
         *
         * @param actorType 业务类型
         */
        public void setActorType(String actorType) { this.actorType = actorType; }
        /**
         * 获取{@code ActorId}。
         *
         * @return 处理结果
         */
        public Long getActorId() { return actorId; }
        /**
         * 设置{@code ActorId}。
         *
         * @param actorId 资源标识
         */
        public void setActorId(Long actorId) { this.actorId = actorId; }
        /**
         * 获取{@code Action}。
         *
         * @return 处理结果
         */
        public String getAction() { return action; }
        /**
         * 设置{@code Action}。
         *
         * @param action {@code action}参数
         */
        public void setAction(String action) { this.action = action; }
        /**
         * 获取资源Type。
         *
         * @return 处理结果
         */
        public String getResourceType() { return resourceType; }
        /**
         * 设置资源Type。
         *
         * @param resourceType 业务类型
         */
        public void setResourceType(String resourceType) { this.resourceType = resourceType; }
        /**
         * 获取资源Id。
         *
         * @return 处理结果
         */
        public Long getResourceId() { return resourceId; }
        /**
         * 设置资源Id。
         *
         * @param resourceId 资源标识
         */
        public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
        /**
         * 获取任务Id。
         *
         * @return 处理结果
         */
        public Long getTaskId() { return taskId; }
        /**
         * 设置任务Id。
         *
         * @param taskId 资源标识
         */
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        /**
         * 获取{@code RunId}。
         *
         * @return 处理结果
         */
        public Long getRunId() { return runId; }
        /**
         * 设置{@code RunId}。
         *
         * @param runId 资源标识
         */
        public void setRunId(Long runId) { this.runId = runId; }
        /**
         * 获取{@code Decision}。
         *
         * @return 处理结果
         */
        public String getDecision() { return decision; }
        /**
         * 设置{@code Decision}。
         *
         * @param decision {@code decision}参数
         */
        public void setDecision(String decision) { this.decision = decision; }
        /**
         * 获取{@code CreatedFrom}。
         *
         * @return 处理结果
         */
        public LocalDateTime getCreatedFrom() { return createdFrom; }
        /**
         * 设置{@code CreatedFrom}。
         *
         * @param createdFrom {@code createdFrom}参数
         */
        public void setCreatedFrom(LocalDateTime createdFrom) { this.createdFrom = createdFrom; }
        /**
         * 获取{@code CreatedTo}。
         *
         * @return 处理结果
         */
        public LocalDateTime getCreatedTo() { return createdTo; }
        /**
         * 设置{@code CreatedTo}。
         *
         * @param createdTo {@code createdTo}参数
         */
        public void setCreatedTo(LocalDateTime createdTo) { this.createdTo = createdTo; }
        /**
         * 获取{@code Limit}。
         *
         * @return 处理结果
         */
        public int getLimit() { return limit; }
        /**
         * 设置{@code Limit}。
         *
         * @param limit 数量上限
         */
        public void setLimit(int limit) { this.limit = limit; }
    }
}
