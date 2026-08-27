package group.aitools.nhs.platform.nhs.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.agent.service.AgentApplicationService;
import group.aitools.nhs.platform.agent.service.AgentExecutionHistoryService;
import group.aitools.nhs.platform.agent.web.AgentExecutionHistoryView;
import group.aitools.nhs.platform.agent.web.AgentView;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.service.ConversationApplicationService;
import group.aitools.nhs.platform.conversation.service.ConversationAttachmentService;
import group.aitools.nhs.platform.conversation.service.ConversationCancellationService;
import group.aitools.nhs.platform.conversation.service.ConversationExportService;
import group.aitools.nhs.platform.conversation.service.ConversationExportService.ConversationExport;
import group.aitools.nhs.platform.conversation.service.ConversationFinalizationService;
import group.aitools.nhs.platform.conversation.service.ConversationGovernanceService;
import group.aitools.nhs.platform.conversation.service.ConversationHistoryDeletionService;
import group.aitools.nhs.platform.conversation.service.ConversationTurnApplicationService;
import group.aitools.nhs.platform.conversation.web.ConversationFeedbackRequest;
import group.aitools.nhs.platform.conversation.web.ConversationCancellationResult;
import group.aitools.nhs.platform.conversation.web.ConversationFinalizeResult;
import group.aitools.nhs.platform.conversation.web.ConversationMessageView;
import group.aitools.nhs.platform.conversation.web.ConversationResourceScopeRequest;
import group.aitools.nhs.platform.conversation.web.ConversationTurnView;
import group.aitools.nhs.platform.conversation.web.ConversationView;
import group.aitools.nhs.platform.conversation.web.CreateConversationRequest;
import group.aitools.nhs.platform.conversation.web.CreateConversationTurnRequest;
import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.service.DataQueryExportService;
import group.aitools.nhs.platform.data.service.DataQueryExportService.ExportedFile;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.data.web.DataSourceView;
import group.aitools.nhs.platform.data.web.DataTableView;
import group.aitools.nhs.platform.data.web.DatasetView;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService.ConversationTrace;
import group.aitools.nhs.platform.execution.service.ExecutionEventSseService;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.execution.web.CreateTaskRunRequest;
import group.aitools.nhs.platform.execution.web.TaskRunActionResult;
import group.aitools.nhs.platform.execution.web.TaskRunView;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.approval.service.ApprovalApplicationService;
import group.aitools.nhs.platform.approval.web.ApprovalDecisionRequest;
import group.aitools.nhs.platform.approval.web.ApprovalDecisionResult;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.PlatformUiPermissionService;
import group.aitools.nhs.platform.nhs.service.NhsWorkspaceService;
import group.aitools.nhs.platform.report.service.ReportApplicationService;
import group.aitools.nhs.platform.report.service.ReportExecutionPrincipalResolver;
import group.aitools.nhs.platform.report.web.ReportSubscriptionView;
import group.aitools.nhs.platform.report.web.ReportView;
import group.aitools.nhs.platform.report.web.UpdateReportSubscriptionStatusRequest;
import group.aitools.nhs.platform.sandbox.service.ExternalExecutionResumeService;
import group.aitools.nhs.platform.sandbox.web.ExternalExecutionResumeResult;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.task.web.CreateTaskRequest;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import group.aitools.nhs.platform.task.web.TaskView;
import group.aitools.nhs.platform.task.web.UpdateTaskRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 提供{@code NhsV1Compatibility}相关的 HTTP 接口，并负责请求校验与结果返回。
 *
 * Nhs V1 compatibility surface backed by the platform application services.
 * The adapter deliberately keeps the Nhs URL and payload vocabulary while
 * preserving the platform's authorization and durable execution semantics.
 */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/v1")
public class NhsV1CompatibilityController {

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final ConversationApplicationService conversationService;
    private final ConversationAttachmentService attachmentService;
    private final ConversationGovernanceService governanceService;
    private final ConversationExportService exportService;
    private final ConversationTurnApplicationService turnService;
    private final ExecutionEventQueryService eventQueryService;
    private final ExecutionEventSseService eventSseService;
    private final AgentApplicationService agentService;
    private final AgentExecutionHistoryService executionHistoryService;
    private final TaskApplicationService taskService;
    private final TaskQueryService taskQueryService;
    private final TaskRunApplicationService runService;
    private final DataSourceCatalogService dataCatalogService;
    private final DataQueryExecutionService dataQueryService;
    private final DataQueryExportService dataQueryExportService;
    private final NhsWorkspaceService workspaceService;
    private final ReportApplicationService reportService;
    private final ReportExecutionPrincipalResolver reportPrincipalResolver;
    private final PlatformUiPermissionService uiPermissionService;
    private final ConversationFinalizationService finalizationService;
    private final ApprovalApplicationService approvalService;
    private final ExternalExecutionResumeService externalExecutionService;
    private final ConversationCancellationService cancellationService;
    private final ConversationHistoryDeletionService historyDeletionService;

    @Autowired
    public NhsV1CompatibilityController(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        ConversationApplicationService conversationService,
        ConversationAttachmentService attachmentService,
        ConversationGovernanceService governanceService,
        ConversationExportService exportService,
        ConversationTurnApplicationService turnService,
        ExecutionEventQueryService eventQueryService,
        ExecutionEventSseService eventSseService,
        AgentApplicationService agentService,
        AgentExecutionHistoryService executionHistoryService,
        TaskApplicationService taskService,
        TaskQueryService taskQueryService,
        TaskRunApplicationService runService,
        DataSourceCatalogService dataCatalogService,
        DataQueryExecutionService dataQueryService,
        DataQueryExportService dataQueryExportService,
        NhsWorkspaceService workspaceService,
        ReportApplicationService reportService,
        ReportExecutionPrincipalResolver reportPrincipalResolver,
        PlatformUiPermissionService uiPermissionService,
        ConversationFinalizationService finalizationService,
        ApprovalApplicationService approvalService,
        ExternalExecutionResumeService externalExecutionService,
        ConversationCancellationService cancellationService,
        ConversationHistoryDeletionService historyDeletionService
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.conversationService = conversationService;
        this.attachmentService = attachmentService;
        this.governanceService = governanceService;
        this.exportService = exportService;
        this.turnService = turnService;
        this.eventQueryService = eventQueryService;
        this.eventSseService = eventSseService;
        this.agentService = agentService;
        this.executionHistoryService = executionHistoryService;
        this.taskService = taskService;
        this.taskQueryService = taskQueryService;
        this.runService = runService;
        this.dataCatalogService = dataCatalogService;
        this.dataQueryService = dataQueryService;
        this.dataQueryExportService = dataQueryExportService;
        this.workspaceService = workspaceService;
        this.reportService = reportService;
        this.reportPrincipalResolver = reportPrincipalResolver;
        this.uiPermissionService = uiPermissionService;
        this.finalizationService = finalizationService;
        this.approvalService = approvalService;
        this.externalExecutionService = externalExecutionService;
        this.cancellationService = cancellationService;
        this.historyDeletionService = historyDeletionService;
    }

    /**
 * 创建 {@code NhsV1CompatibilityController} 实例并初始化所需依赖。
 * Backward-compatible constructor retained for focused adapter tests. */
    public NhsV1CompatibilityController(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        ConversationApplicationService conversationService,
        ConversationAttachmentService attachmentService,
        ConversationGovernanceService governanceService,
        ConversationExportService exportService,
        ConversationTurnApplicationService turnService,
        ExecutionEventQueryService eventQueryService,
        ExecutionEventSseService eventSseService,
        AgentApplicationService agentService,
        TaskApplicationService taskService,
        TaskQueryService taskQueryService,
        TaskRunApplicationService runService,
        DataSourceCatalogService dataCatalogService,
        DataQueryExecutionService dataQueryService,
        DataQueryExportService dataQueryExportService,
        NhsWorkspaceService workspaceService,
        ReportApplicationService reportService,
        ReportExecutionPrincipalResolver reportPrincipalResolver
    ) {
        this(
            principalProvider, idGenerator, conversationService, attachmentService,
            governanceService, exportService, turnService, eventQueryService,
            eventSseService, agentService, null, taskService, taskQueryService, runService,
            dataCatalogService, dataQueryService, dataQueryExportService,
            workspaceService, reportService, reportPrincipalResolver, null, null, null, null, null, null
        );
    }

    /**
 * 创建 {@code NhsV1CompatibilityController} 实例并初始化所需依赖。
 * Focused adapter constructor that exposes the effective UI permission projection. */
    public NhsV1CompatibilityController(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        ConversationApplicationService conversationService,
        ConversationAttachmentService attachmentService,
        ConversationGovernanceService governanceService,
        ConversationExportService exportService,
        ConversationTurnApplicationService turnService,
        ExecutionEventQueryService eventQueryService,
        ExecutionEventSseService eventSseService,
        AgentApplicationService agentService,
        TaskApplicationService taskService,
        TaskQueryService taskQueryService,
        TaskRunApplicationService runService,
        DataSourceCatalogService dataCatalogService,
        DataQueryExecutionService dataQueryService,
        DataQueryExportService dataQueryExportService,
        NhsWorkspaceService workspaceService,
        ReportApplicationService reportService,
        ReportExecutionPrincipalResolver reportPrincipalResolver,
        PlatformUiPermissionService uiPermissionService
    ) {
        this(
            principalProvider, idGenerator, conversationService, attachmentService,
            governanceService, exportService, turnService, eventQueryService,
            eventSseService, agentService, null, taskService, taskQueryService, runService,
            dataCatalogService, dataQueryService, dataQueryExportService,
            workspaceService, reportService, reportPrincipalResolver, uiPermissionService,
            null, null, null, null, null
        );
    }

    /**
     * 处理配置档案并返回对应结果。
     *
     * @param username 名称
     * @return 处理结果
     */
    @GetMapping("/users/profile")
    public R<Map<String, Object>> profile(@RequestParam(required = false) String username) {
        CurrentPrincipal current = principalProvider.currentPrincipal();
        if (username != null && !username.isBlank() && !username.equalsIgnoreCase(current.username())
            && !current.roles().stream().anyMatch(role -> "platform_admin".equals(role.key()))) {
            throw new ServiceException("只能查询自己的用户画像", HttpStatus.FORBIDDEN);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", current.id());
        data.put("username", current.username());
        data.put("display_name", current.username());
        data.put("role", current.roles().stream().anyMatch(role -> "platform_admin".equals(role.key()) ? true : false)
            ? "admin" : "user");
        data.put("status", 1);
        data.put("roles", current.roles().stream().map(role -> role.key()).sorted().toList());
        data.put("permissions", uiPermissionService == null
            ? List.of() : uiPermissionService.buttons(current));
        return R.ok(data);
    }

    /**
     * 处理{@code greeting}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/chat/greeting")
    public R<Map<String, Object>> greeting() {
        return R.ok(Map.of("greeting", "你好，我可以协助你完成查询、分析和任务执行。"));
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param limit 数量上限
     * @param keyword {@code keyword}参数
     * @param search {@code search}参数
     * @param page {@code page}参数
     * @param pageSize 数量上限
     * @param agentId 资源标识
     * @param conversationId 资源标识
     * @param username 名称
     * @param status 目标状态
     * @param startDate {@code startDate}参数
     * @param endDate {@code endDate}参数
     * @param groupByConversation groupBy会话参数
     * @return 处理结果
     */
    @GetMapping("/chat/history")
    public R<?> history(
        @RequestParam(required = false) @Min(1) @Max(200) Integer limit,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) @Min(1) Integer page,
        @RequestParam(name = "page_size", required = false) @Min(1) @Max(100) Integer pageSize,
        @RequestParam(name = "agent_id", required = false) @Positive Long agentId,
        @RequestParam(name = "conversation_id", required = false) @Positive Long conversationId,
        @RequestParam(required = false) String username,
        @RequestParam(required = false) String status,
        @RequestParam(name = "start_date", required = false) String startDate,
        @RequestParam(name = "end_date", required = false) String endDate,
        @RequestParam(name = "group_by_conversation", defaultValue = "false") boolean groupByConversation
    ) {
        String query = keyword == null || keyword.isBlank() ? search : keyword;
        boolean legacyList = (limit != null || search != null || keyword != null)
            && page == null && pageSize == null && agentId == null && conversationId == null
            && username == null && status == null && startDate == null && endDate == null
            && !groupByConversation;
        if (legacyList) {
            int boundedLimit = limit == null ? 50 : limit;
            return R.ok(conversationService.list(query, boundedLimit).stream().map(this::conversation).toList());
        }
        if (executionHistoryService == null) {
            throw new ServiceException("执行历史服务未配置", 503);
        }
        int requestedPage = page == null ? 1 : page;
        int requestedPageSize = pageSize == null ? 20 : pageSize;
        LocalDateTime parsedStart = parseHistoryDate(startDate, false, "start_date");
        LocalDateTime parsedEnd = parseHistoryDate(endDate, true, "end_date");
        AgentExecutionHistoryService.ExecutionHistoryPage result = executionHistoryService.page(
            requestedPage, requestedPageSize, agentId, conversationId, username, query, status,
            parsedStart, parsedEnd, groupByConversation
        );
        return R.ok(Map.of(
            "total", result.total(),
            "page", result.page(),
            "page_size", result.pageSize(),
            "items", result.items().stream().map(this::historyItem).toList()
        ));
    }

    /**
     * 处理历史记录Item并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> historyItem(AgentExecutionHistoryView value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", value.id());
        item.put("trace_id", value.trace_id());
        item.put("agent_id", value.agent_id());
        item.put("conversation_id", value.conversation_id());
        item.put("project_name", null);
        item.put("username", value.username());
        item.put("query", value.query());
        item.put("summary", value.summary());
        item.put("reasoning_content", null);
        item.put("process_timeline", null);
        item.put("status", value.status());
        item.put("agent_version", value.agent_version());
        item.put("model_id", value.model_id());
        item.put("execution_time_ms", value.execution_time_ms());
        item.put("prompt_tokens", value.prompt_tokens());
        item.put("completion_tokens", value.completion_tokens());
        item.put("total_tokens", value.total_tokens());
        item.put("turn_count", value.turn_count());
        item.put("created_at", value.created_at());
        item.put("agent_name", value.agent_name());
        item.put("agent_display_name", value.agent_display_name());
        return item;
    }

    /**
     * 处理parse历史记录Date并返回对应结果。
     *
     * @param value {@code value}参数
     * @param endOfDay {@code endOfDay}参数
     * @param field {@code field}参数
     * @return 处理结果
     */
    private LocalDateTime parseHistoryDate(String value, boolean endOfDay, String field) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    LocalDate date = LocalDate.parse(value);
                    return endOfDay ? date.atTime(23, 59, 59, 999_999_999) : date.atStartOfDay();
                } catch (DateTimeParseException invalid) {
                    throw new ServiceException(field + " 必须是 ISO 8601 日期时间", HttpStatus.BAD_REQUEST);
                }
            }
        }
    }

    /**
     * 处理active会话并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/chat/active")
    public R<Map<String, Object>> activeConversation() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation_id", governanceService.activeConversation());
        return R.ok(result);
    }

    /**
     * 设置Active会话。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chat/active")
    public R<Map<String, Object>> setActiveConversation(@RequestBody Map<String, Object> payload) {
        governanceService.setActiveConversation(requiredNumber(payload, "conversation_id", "会话ID"));
        return R.ok(Map.of("status", "success"));
    }

    /**
     * 删除历史记录。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/chat/history/{conversationId:[0-9]+}")
    public R<Map<String, Object>> deleteHistory(@PathVariable @Positive Long conversationId) {
        if (historyDeletionService != null) {
            ConversationHistoryDeletionService.BatchDeletionResult deleted =
                historyDeletionService.deleteConversations(List.of(conversationId));
            return R.ok(Map.of(
                "conversation_id", conversationId,
                "deleted", deleted.insertedCount() > 0,
                "already_deleted", deleted.insertedCount() == 0,
                "physical_delete", false
            ));
        }
        governanceService.deleteConversation(conversationId);
        return R.ok(Map.of("conversation_id", conversationId, "deleted", true));
    }

    /**
 * 删除历史记录By链路追踪。
 *
     * Nhs deletes a history row by its execution trace. The owner-scoped
     * tombstone hides only that projection; messages, events and Trace facts
     * remain durable and replayable.
     */
    @DeleteMapping("/chat/history/{traceId:[A-Za-z0-9._:-]*[A-Za-z._:-][A-Za-z0-9._:-]*}")
    public R<Map<String, Object>> deleteHistoryByTrace(@PathVariable String traceId) {
        if (historyDeletionService != null) {
            ConversationHistoryDeletionService.DeletionResult deleted =
                historyDeletionService.deleteTrace(traceId);
            return R.ok(Map.of(
                "trace_id", deleted.traceId(),
                "conversation_id", deleted.conversationId(),
                "deleted", deleted.deleted(),
                "already_deleted", deleted.alreadyDeleted(),
                "physical_delete", false
            ));
        }
        ConversationTrace trace = eventQueryService.traceConversation(traceId);
        Long conversationId = trace.turn().getConversationId();
        governanceService.deleteConversation(conversationId);
        return R.ok(Map.of(
            "trace_id", trace.traceId(), "conversation_id", conversationId, "deleted", true
        ));
    }

    /**
     * 删除历史记录Batch。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @DeleteMapping("/chat/history")
    public R<Map<String, Object>> deleteHistoryBatch(@RequestBody Map<String, Object> payload) {
        List<Long> ids = longList(payload == null ? null : payload.get("conversation_ids"));
        if (ids.isEmpty() || ids.size() > 100) {
            throw new ServiceException("会话ID列表不能为空且最多100项", HttpStatus.BAD_REQUEST);
        }
        if (historyDeletionService != null) {
            ConversationHistoryDeletionService.BatchDeletionResult deleted =
                historyDeletionService.deleteConversations(ids);
            return R.ok(Map.of(
                "conversation_ids", deleted.conversationIds(),
                "deleted_count", deleted.requestedCount(),
                "inserted_count", deleted.insertedCount(),
                "physical_delete", false
            ));
        }
        ids.forEach(governanceService::deleteConversation);
        return R.ok(Map.of("conversation_ids", ids, "deleted_count", ids.size()));
    }

    /**
     * 删除历史记录BatchAlias。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chat/history/batch-delete")
    public R<Map<String, Object>> deleteHistoryBatchAlias(@RequestBody Map<String, Object> payload) {
        return deleteHistoryBatch(payload);
    }

    /**
     * 处理会话Detail并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @param offset 起始位置或序号
     * @return 处理结果
     */
    @GetMapping("/chat/conversation/{conversationId}")
    public R<Map<String, Object>> conversationDetail(
        @PathVariable @Positive Long conversationId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        ConversationView conversation = conversationService.get(conversationId);
        List<ConversationMessageView> messages = conversationService.messages(conversationId, offset, limit);
        List<ExecutionEventView> events = eventQueryService.listConversation(
            conversationId, 0, Math.min(1000, Math.max(200, limit * 4))
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(conversation(conversation));
        result.put("messages", messages.stream().map(value -> message(value, events)).toList());
        result.put("attachments", attachmentService.list(conversationId, 100));
        result.put("events", events);
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("has_more", messages.size() == limit);
        return R.ok(result);
    }

    /**
     * 处理active会话回合并返回对应结果。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @GetMapping("/chat/conversation/{conversationId}/active")
    public R<ConversationTurnView> activeTurn(@PathVariable @Positive Long conversationId) {
        return R.ok(turnService.active(conversationId));
    }

    /**
 * 判断cel对话是否满足要求。
 * Nhs global cancel alias; the durable turn stop fact is the source of truth. */
    @PostMapping("/chat/cancel")
    public R<Map<String, Object>> cancelChat(@RequestBody Map<String, Object> payload) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Long conversationId = requiredNumber(payload, "conversation_id", "会话ID");
        if (cancellationService != null) {
            ConversationCancellationResult cancelled = cancellationService.cancel(
                conversationId, text(payload, "trace_id"), text(payload, "reason")
            );
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("conversation_id", cancelled.conversationId());
            result.put("trace_id", cancelled.traceId());
            result.put("success", cancelled.success());
            result.put("lane_released", cancelled.laneReleased());
            result.put("session_locks_released", cancelled.sessionLocksReleased());
            result.put("run_cancelled", cancelled.runCancelled());
            result.put("canvas_stopped", cancelled.canvasStopped());
            result.put("task_runs_cancelled", cancelled.taskRunsCancelled());
            result.put("status", cancelled.status());
            result.put("reason", cancelled.reason());
            if (cancelled.turnId() != null) {
                result.put("turn_id", cancelled.turnId());
            }
            return R.ok(result);
        }
        ConversationTurnView active = turnService.active(conversationId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation_id", conversationId);
        result.put("trace_id", text(payload, "trace_id"));
        result.put("session_locks_released", 0);
        result.put("canvas_stopped", 0);
        if (active == null) {
            result.put("success", false);
            result.put("lane_released", false);
            result.put("run_cancelled", false);
            result.put("reason", "no_active_turn");
            return R.ok(result);
        }
        String requestedTrace = text(payload, "trace_id");
        if (requestedTrace != null && !requestedTrace.equals(active.traceId())) {
            throw new ServiceException("Trace ID与当前会话回合不匹配", HttpStatus.CONFLICT);
        }
        ConversationTurnView stopped = turnService.stop(
            conversationId, active.id(), text(payload, "reason")
        );
        boolean terminal = List.of("succeeded", "failed", "cancelled")
            .contains(stopped.status());
        result.put("success", true);
        result.put("lane_released", terminal);
        result.put("run_cancelled", "cancelled".equals(stopped.status()));
        result.put("status", stopped.status());
        result.put("turn_id", stopped.id());
        result.put("reason", terminal ? "cancel_requested" : "stop_requested");
        return R.ok(result);
    }

    /**
     * 判断cel会话回合是否满足要求。
     *
     * @param conversationId 资源标识
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chat/conversation/{conversationId}/cancel")
    public R<ConversationTurnView> cancelTurn(
        @PathVariable @Positive Long conversationId,
        @RequestBody(required = false) Map<String, Object> payload
    ) {
        Long turnId = number(payload, "turn_id");
        if (turnId == null) {
            ConversationTurnView active = turnService.active(conversationId);
            if (active == null) {
                throw new ServiceException("会话没有运行中的回合", HttpStatus.CONFLICT);
            }
            turnId = active.id();
        }
        return R.ok(turnService.stop(conversationId, turnId, text(payload, "reason")));
    }

    /**
     * 处理finalize会话并返回对应结果。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @PostMapping("/chat/conversation/{conversationId}/finalize")
    public R<ConversationFinalizeResult> finalizeConversation(
        @PathVariable @Positive Long conversationId
    ) {
        if (finalizationService == null) {
            throw new ServiceException("会话Finalize服务未启用", 503);
        }
        return R.ok(finalizationService.finalizeConversation(conversationId));
    }

    /**
 * 处理confirm工具权限并返回对应结果。
 * Nhs ASK-tool confirmation adapter backed by the durable approval snapshot. */
    @PostMapping(value = "/chat/permissions/{permissionRequestId}/confirm",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter confirmToolPermission(
        @PathVariable String permissionRequestId,
        @RequestBody Map<String, Object> payload,
        @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (approvalService == null) {
            throw new ServiceException("审批服务未启用", 503);
        }
        long approvalId = parseApprovalId(permissionRequestId);
        Object confirmedValue = payload == null ? null : payload.get("confirmed");
        if (!(confirmedValue instanceof Boolean confirmed)) {
            throw new ServiceException("confirmed必须为布尔值", HttpStatus.BAD_REQUEST);
        }
        String idempotencyKey = text(payload, "idempotency_key");
        if (idempotencyKey == null) {
            idempotencyKey = "nhs-permission-" + approvalId + "-" + confirmed;
        }
        String comment = text(payload, "comment");
        long cursor = confirmed ? resumeCursor(payload, lastEventId) : 0;
        ApprovalDecisionRequest request = new ApprovalDecisionRequest(idempotencyKey, comment);
        ApprovalDecisionResult decision = confirmed
            ? approvalService.approve(approvalId, request)
            : approvalService.reject(approvalId, request);
        if (decision.approval() == null || decision.approval().runId() == null
            || decision.approval().taskId() == null) {
            throw new ServiceException("审批没有可恢复的运行身份", HttpStatus.CONFLICT);
        }
        if (!confirmed) {
            return rejectedApprovalStream(decision);
        }
        return eventSseService.streamNhs(
            eventQueryService.taskRunReader(
                decision.approval().taskId(), decision.approval().runId()
            ), cursor
        );
    }

    /**
 * 处理resumeExternal执行并返回对应结果。
 * Resumes a server-owned external execution request with validated results. */
    @PostMapping(value = "/chat/external-executions/{externalExecutionRequestId}/resume",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeExternalExecution(
        @PathVariable String externalExecutionRequestId,
        @RequestBody Map<String, Object> payload,
        @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (externalExecutionService == null) {
            throw new ServiceException("外部执行恢复服务未启用", 503);
        }
        Object rawResults = payload == null ? null : payload.get("results");
        if (!(rawResults instanceof List<?> list)) {
            throw new ServiceException("results不能为空", HttpStatus.BAD_REQUEST);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new ServiceException("results必须是对象列表", HttpStatus.BAD_REQUEST);
            }
            Map<String, Object> value = new LinkedHashMap<>();
            map.forEach((key, val) -> value.put(String.valueOf(key), val));
            results.add(value);
        }
        long cursor = resumeCursor(payload, lastEventId);
        ExternalExecutionResumeResult resumed = externalExecutionService.resume(
            externalExecutionRequestId, results
        );
        return eventSseService.streamNhs(
            eventQueryService.taskRunReader(resumed.taskId(), resumed.runId()), cursor
        );
    }

    /**
     * 处理资源范围并返回对应结果。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @GetMapping("/chat/conversation/{conversationId}/resource-scope")
    public R<?> resourceScope(@PathVariable @Positive Long conversationId) {
        return R.ok(governanceService.resourceScope(conversationId));
    }

    /**
     * 更新资源范围。
     *
     * @param conversationId 资源标识
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PutMapping("/chat/conversation/{conversationId}/resource-scope")
    public R<?> updateResourceScope(
        @PathVariable @Positive Long conversationId,
        @RequestBody Map<String, Object> payload
    ) {
        return R.ok(governanceService.updateResourceScope(
            conversationId,
            new ConversationResourceScopeRequest(
                integer(payload, "expected_revision"), resourceMap(payload.get("resources"))
            )
        ));
    }

    /**
     * 处理反馈并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chat/feedback")
    public R<?> feedback(@RequestBody Map<String, Object> payload) {
        Long conversationId = requiredNumber(payload, "conversation_id", "会话ID");
        return R.ok(governanceService.saveFeedback(conversationId, new ConversationFeedbackRequest(
            number(payload, "message_id"), number(payload, "turn_id"),
            required(payload, "rating", "反馈类型"), text(payload, "reason"),
            text(payload, "comment"), text(payload, "trace_id")
        )));
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param file 文件参数
     * @return 处理结果
     */
    @PostMapping(value = "/chat/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<?> upload(
        @RequestParam("conversation_id") @Positive Long conversationId,
        @RequestPart("file") MultipartFile file
    ) {
        return R.ok(attachmentService.upload(conversationId, file));
    }

    /**
     * 处理导出并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chat/export")
    public ResponseEntity<byte[]> export(@RequestBody Map<String, Object> payload) {
        Long conversationId = requiredNumber(payload, "conversation_id", "会话ID");
        ConversationExport exported = exportService.export(conversationId, text(payload, "format"));
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(exported.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(MediaType.parseMediaType(exported.mediaType()))
            .contentLength(exported.content().length)
            .body(exported.content());
    }

    /**
     * 处理链路追踪Logs并返回对应结果。
     *
     * @param traceId 资源标识
     * @return 处理结果
     */
    @GetMapping("/chat/logs/{traceId}")
    public R<Map<String, Object>> traceLogs(@PathVariable String traceId) {
        return R.ok(traceLog(eventQueryService.traceConversation(traceId)));
    }

    /**
     * 处理导出链路追踪数据并返回对应结果。
     *
     * @param traceId 资源标识
     * @param format {@code format}参数
     * @return 处理结果
     */
    @GetMapping("/chat/export/data/{traceId}")
    public ResponseEntity<byte[]> exportTraceData(
        @PathVariable String traceId,
        @RequestParam(defaultValue = "xlsx") String format
    ) {
        ExportedFile exported = dataQueryExportService.exportTrace(traceId, format);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(exported.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(MediaType.parseMediaType(exported.mediaType()))
            .contentLength(exported.content().length)
            .body(exported.content());
    }

    /**
     * 处理模型Calls并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param cursor {@code cursor}参数
     * @param traceId 资源标识
     * @return 处理结果
     */
    @GetMapping("/chat/conversation/{conversationId}/model_calls")
    public R<Map<String, Object>> modelCalls(
        @PathVariable @Positive Long conversationId,
        @RequestParam(defaultValue = "0") @Min(0) long cursor,
        @RequestParam(name = "trace_id", required = false) String traceId
    ) {
        List<ExecutionEventView> events = eventQueryService.listConversation(conversationId, cursor, 200);
        return R.ok(Map.of("stats", modelCallStats(events, traceId)));
    }

    /**
 * 处理模型CallStats并返回对应结果。
 *
     * Projects the durable execution events into the legacy Nhs model-call
     * stats shape. The event stream contains start/end fragments, so calls are
     * correlated by replyId before the compatibility fields are returned.
     */
    private List<Map<String, Object>> modelCallStats(
        List<ExecutionEventView> events,
        String requestedTraceId
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        String traceId = requestedTraceId == null || requestedTraceId.isBlank()
            ? null : requestedTraceId.strip();
        Map<String, ModelCallAccumulator> calls = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> toolNames = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> toolCalls = new LinkedHashMap<>();
        if (events == null) {
            return List.of();
        }
        for (ExecutionEventView event : events) {
            if (traceId != null && !traceId.equals(event.traceId())) {
                continue;
            }
            Map<String, Object> values = event.projection().isEmpty()
                ? event.payload() : event.projection();
            String replyId = textValue(values, "replyId", "reply_id");
            if (isModelCallEvent(event)) {
                String key = replyId == null
                    ? "event:" + event.cursor() : "reply:" + replyId;
                calls.computeIfAbsent(key, ignored -> new ModelCallAccumulator(event))
                    .accept(event, values);
            } else if (isToolCallEvent(event) && replyId != null) {
                String toolName = textValue(values, "toolName", "tool_name", "name");
                if (toolName == null) {
                    continue;
                }
                toolNames.computeIfAbsent(replyId, ignored -> new LinkedHashSet<>()).add(toolName);
                if ("tool_call_started".equals(event.eventType()) || "tool_call".equals(event.eventType())) {
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("name", toolName);
                    Object arguments = firstValue(values, "toolInput", "tool_input", "arguments", "input");
                    if (arguments != null) {
                        call.put("arguments", arguments);
                    }
                    toolCalls.computeIfAbsent(replyId, ignored -> new ArrayList<>()).add(call);
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>(calls.size());
        int callIndex = 1;
        for (ModelCallAccumulator call : calls.values()) {
            String replyId = call.replyId();
            result.add(call.toStats(
                callIndex++,
                toolNames.getOrDefault(replyId, new LinkedHashSet<>()),
                toolCalls.getOrDefault(replyId, List.of())
            ));
        }
        return List.copyOf(result);
    }

    /**
     * 判断模型Call事件是否满足要求。
     *
     * @param event 事件参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isModelCallEvent(ExecutionEventView event) {
        String type = event.eventType();
        return "model_call".equals(type) || (type != null && type.startsWith("model_call_"));
    }

    /**
     * 判断工具Call事件是否满足要求。
     *
     * @param event 事件参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isToolCallEvent(ExecutionEventView event) {
        String type = event.eventType();
        return "tool_call".equals(type)
            || type != null && (type.startsWith("tool_call_") || type.startsWith("tool_result_"));
    }

    /**
     * 处理{@code textValue}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private String textValue(Map<String, Object> values, String... keys) {
        Object value = firstValue(values, keys);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    /**
     * 表示模型CallAccumulator相关的领域对象。
     */
    private final class ModelCallAccumulator {

        private final ExecutionEventView first;
        private final Map<String, Object> values = new LinkedHashMap<>();
        private LocalDateTime lastOccurredAt;

        /**
         * 创建 {@code ModelCallAccumulator} 实例并初始化所需依赖。
         *
         * @param first {@code first}参数
         */
        private ModelCallAccumulator(ExecutionEventView first) {
            this.first = first;
            this.lastOccurredAt = first.occurredAt();
        }

        /**
         * 处理{@code accept}相关逻辑。
         *
         * @param event 事件参数
         * @param source 数据源参数
         */
        private void accept(ExecutionEventView event, Map<String, Object> source) {
            for (String key : List.of(
                "replyId", "reply_id", "agentName", "agent_name", "model", "modelName",
                "model_name", "temperature", "inputMessageCount", "input_message_count",
                "messageCount", "promptTokens", "prompt_tokens", "completionTokens",
                "completion_tokens", "cachedTokens", "cacheInputTokens", "cache_input_tokens",
                "totalTokens", "total_tokens", "durationMs", "duration_ms", "elapsedMs",
                "elapsed_ms", "responseText", "response_text", "outputText", "output",
                "reasoningContent", "reasoning_content", "hasToolsBound", "has_tools_bound"
            )) {
                Object value = source.get(key);
                if (value != null) {
                    values.put(key, value);
                }
            }
            if (event.occurredAt() != null
                && (lastOccurredAt == null || event.occurredAt().isAfter(lastOccurredAt))) {
                lastOccurredAt = event.occurredAt();
            }
        }

        /**
         * 处理{@code replyId}并返回对应结果。
         *
         * @return 处理结果
         */
        private String replyId() {
            return textValue(values, "replyId", "reply_id");
        }

        /**
         * 将输入数据转换为{@code Stats}。
         *
         * @param callIndex {@code callIndex}参数
         * @param toolNames 名称
         * @param toolCalls 工具Calls参数
         * @return 处理结果
         */
        private Map<String, Object> toStats(
            int callIndex,
            Set<String> toolNames,
            List<Map<String, Object>> toolCalls
        ) {
            int inputTokens = integerValue(values, "promptTokens", "prompt_tokens", "inputTokens", "input_tokens");
            int outputTokens = integerValue(values, "completionTokens", "completion_tokens", "outputTokens", "output_tokens");
            int cachedTokens = integerValue(values, "cachedTokens", "cacheInputTokens", "cache_input_tokens");
            int totalTokens = integerValue(values, "totalTokens", "total_tokens");
            if (totalTokens == 0) {
                totalTokens = inputTokens + outputTokens;
            }
            Number elapsed = firstNumber(values, "durationMs", "duration_ms", "elapsedMs", "elapsed_ms");
            if (elapsed == null) {
                elapsed = elapsedMillis(first.occurredAt(), lastOccurredAt);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("call_index", callIndex);
            result.put("timestamp", first.occurredAt());
            result.put("conversation_id", first.conversationId() == null ? null : String.valueOf(first.conversationId()));
            result.put("agent_name", textValue(values, "agentName", "agent_name", "agent"));
            result.put("model_name", textValue(values, "modelName", "model_name", "model"));
            result.put("input_message_count", integerValue(values,
                "inputMessageCount", "input_message_count", "messageCount"));
            result.put("has_tools_bound", booleanValue(values, "hasToolsBound", "has_tools_bound") || !toolNames.isEmpty());
            result.put("input_tokens", inputTokens);
            result.put("output_tokens", outputTokens);
            result.put("cache_input_tokens", cachedTokens);
            result.put("total_tokens", totalTokens);
            result.put("has_tool_calls", !toolNames.isEmpty());
            result.put("tool_names", List.copyOf(toolNames));
            result.put("elapsed_ms", elapsed);
            result.put("trace_id", first.traceId());
            result.put("response_text", textValue(values, "responseText", "response_text", "outputText", "output", "text"));
            result.put("reasoning_content", textValue(values, "reasoningContent", "reasoning_content"));
            result.put("tool_calls", List.copyOf(toolCalls));
            return result;
        }
    }

    /**
     * 处理{@code completions}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @param lastEventId 资源标识
     * @return 处理结果
     */
    @PostMapping(
        value = "/chat/completions",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE,
        headers = "Accept=text/event-stream"
    )
    public SseEmitter completions(
        @RequestBody Map<String, Object> payload,
        @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Long conversationId = number(payload, "conversation_id");
        Long agentId = number(payload, "agent_id");
        Long agentVersionId = number(payload, "agent_version_id");
        long afterCursor = resumeCursor(payload, lastEventId);
        // A zero cursor is a valid reconnect point.  Conversation identity,
        // not cursor magnitude, distinguishes resume from a new turn.
        boolean resumeOnly = conversationId != null && !hasInput(payload);
        if (conversationId == null && resumeOnly) {
            throw new ServiceException("恢复事件流必须提供会话ID", HttpStatus.BAD_REQUEST);
        }
        if (conversationId == null) {
            ConversationView conversation = conversationService.create(
                new CreateConversationRequest(text(payload, "title"), null, agentId, agentVersionId)
            );
            conversationId = conversation.id();
        }
        if (!resumeOnly) {
            String input = input(payload);
            String idempotencyKey = text(payload, "idempotency_key");
            if (idempotencyKey == null) {
                idempotencyKey = "nhs-" + idGenerator.nextUuid();
            }
            turnService.start(conversationId, new CreateConversationTurnRequest(
                idempotencyKey, input, agentId, agentVersionId, longList(payload.get("attachment_ids"))
            ));
        }
        return eventSseService.streamNhs(eventQueryService.conversationReader(conversationId), afterCursor);
    }

    /**
 * 处理{@code completionJson}并返回对应结果。
 *
     * Nhs clients that set Accept: application/json expect one durable completion envelope.
     * The turn is still launched through the same idempotent execution path as SSE; this method
     * only waits for the persisted terminal fact and projects the owner-scoped assistant message.
     */
    @PostMapping(
        value = "/chat/completions",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public R<Map<String, Object>> completionJson(@RequestBody Map<String, Object> payload) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Long conversationId = number(payload, "conversation_id");
        Long agentId = number(payload, "agent_id");
        Long agentVersionId = number(payload, "agent_version_id");
        if (conversationId == null) {
            ConversationView conversation = conversationService.create(
                new CreateConversationRequest(text(payload, "title"), null, agentId, agentVersionId)
            );
            conversationId = conversation.id();
        }
        String idempotencyKey = text(payload, "idempotency_key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = "nhs-" + idGenerator.nextUuid();
        }
        ConversationTurnView started = turnService.start(conversationId, new CreateConversationTurnRequest(
            idempotencyKey, input(payload), agentId, agentVersionId, longList(payload.get("attachment_ids"))
        ));
        ConversationTurnView terminal = awaitJsonCompletion(conversationId, started.id());
        if (!"succeeded".equals(terminal.status())) {
            throw new ServiceException(
                "非流式对话未成功结束：" + terminal.status(),
                "cancelled".equals(terminal.status()) ? HttpStatus.CONFLICT : 502
            );
        }
        ConversationMessageView assistant = findAssistantMessage(conversationId, terminal.traceId());
        if (assistant == null) {
            throw new ServiceException("非流式对话未生成助手消息", 502);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", assistant.content() == null ? "" : assistant.content());
        result.put("intent", "chat");
        result.put("confidence", null);
        result.put("reasoning", null);
        result.put("model", assistant.modelId() == null ? null : String.valueOf(assistant.modelId()));
        result.put("trace_id", terminal.traceId());
        result.put("conversation_id", conversationId);
        result.put("turn_id", terminal.id());
        result.put("status", terminal.status());
        return R.ok(result);
    }

    /**
     * 处理{@code awaitJsonCompletion}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    private ConversationTurnView awaitJsonCompletion(Long conversationId, Long turnId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        ConversationTurnView current = turnService.get(conversationId, turnId);
        while (current != null && !terminalTurn(current.status())) {
            if (System.nanoTime() >= deadline) {
                throw new ServiceException("非流式对话等待超时，请使用 SSE 游标恢复", 504);
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ServiceException("非流式对话被中断", 503);
            }
            current = turnService.get(conversationId, turnId);
        }
        if (current == null) {
            throw new ServiceException("对话回合不存在", HttpStatus.NOT_FOUND);
        }
        return current;
    }

    /**
     * 获取Assistant消息。
     *
     * @param conversationId 资源标识
     * @param traceId 资源标识
     * @return 处理结果
     */
    private ConversationMessageView findAssistantMessage(Long conversationId, String traceId) {
        List<ConversationMessageView> messages = conversationService.messages(conversationId, 0, 500);
        for (int index = messages.size() - 1; index >= 0; index--) {
            ConversationMessageView message = messages.get(index);
            if ("assistant".equals(message.role())
                && (traceId == null || traceId.equals(message.traceId()))) {
                return message;
            }
        }
        return null;
    }

    /**
     * 处理terminal会话回合并返回对应结果。
     *
     * @param status 目标状态
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean terminalTurn(String status) {
        return Set.of("succeeded", "failed", "cancelled").contains(status);
    }

    /**
     * 处理智能体对话并返回对应结果。
     *
     * @param agentId 资源标识
     * @param payload {@code payload}参数
     * @param lastEventId 资源标识
     * @return 处理结果
     */
    @PostMapping(value = "/chat/agents/{agentId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentChat(
        @PathVariable @Positive Long agentId,
        @RequestBody Map<String, Object> payload,
        @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        Map<String, Object> request = new LinkedHashMap<>(payload);
        request.put("agent_id", agentId);
        return completions(request, lastEventId);
    }

    /**
     * 处理{@code schema}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/schema")
    public R<Map<String, Object>> schema(@RequestBody(required = false) Map<String, Object> payload) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (payload != null && "ragflow".equalsIgnoreCase(text(payload, "metadata_provider"))) {
            throw new ServiceException("RAGFlow 未配置", 503);
        }
        String query = payload == null ? null : text(payload, "query");
        List<DatasetView> datasets = dataCatalogService.listDatasets(200).stream()
            .filter(item -> query == null || query.isBlank()
                || item.name().toLowerCase().contains(query.toLowerCase())
                || item.datasetKey().toLowerCase().contains(query.toLowerCase()))
            .toList();
        List<Map<String, Object>> hits = datasets.stream().map(item -> {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", item.id());
            hit.put("name", item.datasetKey());
            hit.put("display_name", item.name());
            return hit;
        }).toList();
        StringBuilder context = new StringBuilder("datasets:\n");
        for (DatasetView dataset : datasets) {
            context.append("  - id: ").append(dataset.id()).append("\n")
                .append("    name: ").append(dataset.name()).append("\n");
            for (DataTableView table : dataCatalogService.metadata(dataset.id())) {
                context.append("    tables:\n      - name: ").append(table.physicalName()).append("\n");
                if (!table.columns().isEmpty()) {
                    context.append("        columns: ");
                    context.append(table.columns().stream().map(column -> column.physicalName()).toList());
                    context.append("\n");
                }
            }
        }
        return R.ok(Map.of(
            "schema_context", context.toString(),
            "hits", hits,
            "provider", "local",
            "logs", List.of("[Metadata Gateway] Routing request to provider: LOCAL")
        ));
    }

    /**
     * 处理对话Bi并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chatbi")
    public R<Map<String, Object>> chatBi(@RequestBody Map<String, Object> payload) {
        if ("ragflow".equalsIgnoreCase(text(payload, "metadata_provider"))) {
            throw new ServiceException("RAGFlow 未配置", 503);
        }
        Long datasetId = number(payload, "dataset_id");
        String sql = text(payload, "sql");
        if (datasetId == null || sql == null) {
            return R.ok(Map.of("status", "clarify", "message", "请提供可查询的数据集和只读 SQL"));
        }
        DataQueryResultView result = dataQueryService.execute(new DataQueryRequest(
            datasetId, null, null, null, input(payload), sql
        ));
        return R.ok(Map.of(
            "status", "success", "query_id", result.queryId(), "columns", result.columns(),
            "rows", result.rows(), "row_count", result.rowCount(), "elapsed_ms", result.elapsedMs()
        ));
    }

    /**
     * 执行对话BiSql相关的处理流程。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chatbi/sql/execute")
    public Map<String, Object> executeChatBiSql(@RequestBody Map<String, Object> payload) {
        String sql = required(payload, "sql", "SQL");
        String dataSource = required(payload, "data_source", "数据源");
        required(payload, "sessionid", "会话ID");
        DatasetView dataset = resolveChatBiDataset(dataSource, text(payload, "dataset_name"));
        DataQueryResultView result = dataQueryService.execute(new DataQueryRequest(
            dataset.id(), null, null, null, "ChatBI 直接查询", sql
        ));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", result.columns());
        data.put("items", result.rows());
        data.put("rows", result.rows());
        data.put("query_id", result.queryId());
        data.put("row_count", result.rowCount());
        data.put("elapsed_ms", result.elapsedMs());
        data.put("truncated", result.truncated());
        return nhsSqlSuccess(data);
    }

    /**
     * 校验对话BiSql授权，并在条件不满足时终止处理。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chatbi/sql/checkauth")
    public Map<String, Object> checkChatBiSqlAuthorization(@RequestBody Map<String, Object> payload) {
        String username = required(payload, "username", "用户名");
        String sql = required(payload, "sql", "SQL");
        String dataSource = required(payload, "data_source", "数据源");
        CurrentPrincipal current = principalProvider.currentPrincipal();
        boolean ownUser = username.equalsIgnoreCase(current.username());
        CurrentPrincipal target = current;
        if (!ownUser) {
            boolean admin = current.roles().stream().anyMatch(role -> "platform_admin".equals(role.key()));
            if (!admin) {
                throw new ServiceException("只能校验自己的 SQL 权限", HttpStatus.FORBIDDEN);
            }
            target = reportPrincipalResolver.resolve(username);
        }
        DatasetView dataset = resolveChatBiDataset(dataSource, text(payload, "dataset_name"));
        DataQueryRequest request = new DataQueryRequest(
            dataset.id(), null, null, null, "ChatBI SQL 权限校验", sql
        );
        if (ownUser) {
            dataQueryService.validate(request);
        } else {
            dataQueryService.validateForPrincipal(target, request);
        }
        return nhsSqlSuccess(Map.of("allowed", true));
    }

    /**
     * 创建并保存任务。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/tasks/")
    public R<Map<String, Object>> createTask(@RequestBody Map<String, Object> payload) {
        AgentView agent = resolveAgent(number(payload, "agent_id"));
        CreateTaskRequest request = new CreateTaskRequest(
            text(payload, "idempotency_key") == null ? "nhs-" + UUID.randomUUID() : text(payload, "idempotency_key"),
            required(payload, "name", "任务名称"), required(payload, "prompt", "任务提示"), null,
            null, agent.publishedVersionId(), null, "enterprise_shared", "general", "single_agent",
            "L1_short_task", "R1", "human", 0, 0, null, Map.of(), List.of(), Map.of(), Map.of(),
            Map.of(), Map.of("nhs", true), List.of()
        );
        TaskMutationResult result = taskService.create(request);
        return R.ok(task(result.task()));
    }

    /**
     * 处理{@code tasks}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/tasks/")
    public R<List<Map<String, Object>>> tasks() {
        return R.ok(taskQueryService.list(200).stream().map(this::task).toList());
    }

    /**
     * 获取任务。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @GetMapping("/tasks/{taskId}")
    public R<Map<String, Object>> getTask(@PathVariable @Positive Long taskId) {
        return R.ok(task(taskQueryService.get(taskId)));
    }

    /**
     * 更新任务。
     *
     * @param taskId 资源标识
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PutMapping("/tasks/{taskId}")
    public R<Map<String, Object>> updateTask(
        @PathVariable @Positive Long taskId,
        @RequestBody Map<String, Object> payload
    ) {
        TaskView current = taskQueryService.get(taskId);
        AgentView agent = resolveAgent(number(payload, "agent_id"));
        UpdateTaskRequest request = new UpdateTaskRequest(
            textOr(payload, "name", current.title()), textOr(payload, "prompt", current.objective()), current.background(),
            current.projectId(), agent.publishedVersionId(), null, current.visibility(), current.category(),
            current.orchestrationMode(), current.lifecycleLevel(), current.riskLevel(), current.acceptanceMode(),
            current.importance(), current.urgency(), current.startAt(), current.contextSnapshot(), List.of(),
            current.acceptanceConfig(), Map.of(), current.budget(), current.externalRefs(), current.tags()
        );
        return R.ok(task(taskService.update(taskId, request).task()));
    }

    /**
     * 处理patch任务并返回对应结果。
     *
     * @param taskId 资源标识
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PatchMapping("/tasks/{taskId}")
    public R<Map<String, Object>> patchTask(
        @PathVariable @Positive Long taskId,
        @RequestBody Map<String, Object> payload
    ) {
        return updateTask(taskId, payload);
    }

    /**
     * 删除任务。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/tasks/{taskId}")
    public R<Map<String, Object>> deleteTask(@PathVariable @Positive Long taskId) {
        return R.ok(task(taskService.updateStatus(taskId, "archived")));
    }

    /**
     * 执行任务相关的处理流程。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @PostMapping("/tasks/{taskId}/run")
    public R<Map<String, Object>> runTask(@PathVariable @Positive Long taskId) {
        TaskRunActionResult result = runService.create(taskId, new CreateTaskRunRequest(
            "nhs-run-" + UUID.randomUUID(), "Nhs V1 手动运行"
        ));
        TaskRunActionResult started = runService.start(taskId, result.run().id());
        return R.ok(run(started.run()));
    }

    /**
     * 处理任务Logs并返回对应结果。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/tasks/{taskId}/logs")
    public R<List<Map<String, Object>>> taskLogs(
        @PathVariable @Positive Long taskId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        List<TaskRunView> runs = runService.list(taskId, Math.min(limit, 200));
        return R.ok(runs.stream().map(this::run).toList());
    }

    /**
     * 处理报表Subscriptions并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/tasks/report-subscriptions")
    public R<List<Map<String, Object>>> reportSubscriptions() {
        return R.ok(reportService.visibleSubscriptions(500).stream().map(this::subscription).toList());
    }

    /**
     * 更新报表SubscriptionStatus。
     *
     * @param subscriptionId 资源标识
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PatchMapping("/tasks/report-subscriptions/{subscriptionId}/status")
    public R<Map<String, Object>> updateReportSubscriptionStatus(
        @PathVariable @Positive Long subscriptionId,
        @RequestBody Map<String, Object> payload
    ) {
        Object raw = payload == null ? null : payload.get("active");
        boolean active = raw instanceof Boolean value ? value : "true".equalsIgnoreCase(String.valueOf(raw));
        ReportSubscriptionView updated = reportService.updateSubscriptionStatus(
            subscriptionId, new UpdateReportSubscriptionStatusRequest(active ? "active" : "paused")
        );
        return R.ok(Map.of("success", true, "status", updated.status()));
    }

    /**
     * 执行报表Subscription相关的处理流程。
     *
     * @param subscriptionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/tasks/report-subscriptions/{subscriptionId}/run")
    public R<Map<String, Object>> runReportSubscription(@PathVariable @Positive Long subscriptionId) {
        DataQueryResultView result = reportService.executeSubscription(subscriptionId);
        return R.ok(Map.of(
            "message", "报表订阅已执行", "query_id", result.queryId(),
            "row_count", result.rowCount(), "elapsed_ms", result.elapsedMs()
        ));
    }

    /**
     * 删除报表Subscription。
     *
     * @param subscriptionId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/tasks/report-subscriptions/{subscriptionId}")
    public R<Map<String, Object>> deleteReportSubscription(@PathVariable @Positive Long subscriptionId) {
        reportService.deleteSubscription(subscriptionId);
        return R.ok(Map.of("success", true));
    }

    /**
     * 处理执行历史记录并返回对应结果。
     *
     * @param page {@code page}参数
     * @param pageSize 数量上限
     * @param status 目标状态
     * @param taskId 资源标识
     * @param q {@code q}参数
     * @param startAt {@code startAt}参数
     * @param endAt {@code endAt}参数
     * @return 处理结果
     */
    @GetMapping("/tasks/execution-history")
    public R<Map<String, Object>> executionHistory(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) String status,
        @RequestParam(name = "task_id", required = false) @Positive Long taskId,
        @RequestParam(required = false) String q,
        @RequestParam(name = "start_at", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
        @RequestParam(name = "end_at", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt
    ) {
        CurrentPrincipal actor = principalProvider.currentPrincipal();
        boolean admin = actor.roles().stream().anyMatch(role -> "platform_admin".equals(role.key()));
        List<TaskView> tasks = taskId == null ? taskQueryService.list(500) : List.of(taskQueryService.get(taskId));
        String normalizedStatus = "success".equalsIgnoreCase(status) ? "succeeded" : status;
        String needle = q == null ? null : q.strip().toLowerCase();
        List<TaskExecutionProjection> matching = tasks.stream()
            .filter(task -> admin || actor.id().equals(task.ownerId()))
            .flatMap(task -> runService.list(task.id(), 500).stream().map(run -> new TaskExecutionProjection(task, run)))
            .filter(item -> normalizedStatus == null || normalizedStatus.isBlank()
                || normalizedStatus.equalsIgnoreCase(item.run().status()))
            .filter(item -> startAt == null || !item.run().createdAt().isBefore(startAt))
            .filter(item -> endAt == null || !item.run().createdAt().isAfter(endAt))
            .filter(item -> needle == null || needle.isBlank() || matches(item, needle))
            .sorted(Comparator.comparing((TaskExecutionProjection item) -> item.run().createdAt()).reversed())
            .toList();
        int from = Math.min((page - 1) * pageSize, matching.size());
        int to = Math.min(from + pageSize, matching.size());
        return R.ok(Map.of(
            "items", matching.subList(from, to).stream().map(this::executionHistoryItem).toList(),
            "total", matching.size(), "page", page, "page_size", pageSize
        ));
    }

    /**
     * 查询{@code Files}列表。
     *
     * @param path {@code path}参数
     * @return 处理结果
     */
    @GetMapping("/chat/fs/list")
    public R<List<Map<String, Object>>> listFiles(@RequestParam(required = false) String path) {
        return R.ok(workspaceService.list(path));
    }

    /**
     * 处理preview文件并返回对应结果。
     *
     * @param path {@code path}参数
     * @return 处理结果
     */
    @GetMapping("/chat/fs/preview")
    public R<Map<String, Object>> previewFile(@RequestParam String path) {
        return R.ok(workspaceService.preview(path));
    }

    /**
     * 处理write文件并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PutMapping("/chat/fs/write")
    public R<Map<String, Object>> writeFile(@RequestBody Map<String, Object> payload) {
        return R.ok(workspaceService.write(required(payload, "path", "文件路径"), text(payload, "content")));
    }

    /**
     * 创建并保存{@code Entry}。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chat/fs/create-entry")
    public R<Map<String, Object>> createEntry(@RequestBody Map<String, Object> payload) {
        return R.ok(workspaceService.createEntry(
            text(payload, "parent_path"), required(payload, "name", "名称"), text(payload, "kind")
        ));
    }

    /**
     * 删除{@code Entry}。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chat/fs/delete-entry")
    public R<Map<String, Object>> deleteEntry(@RequestBody Map<String, Object> payload) {
        return R.ok(workspaceService.delete(required(payload, "path", "文件路径")));
    }

    /**
     * 处理{@code renameEntry}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chat/fs/rename-entry")
    public R<Map<String, Object>> renameEntry(@RequestBody Map<String, Object> payload) {
        String name = text(payload, "new_name");
        if (name == null) {
            name = required(payload, "name", "新名称");
        }
        return R.ok(workspaceService.rename(
            required(payload, "path", "文件路径"), name
        ));
    }

    /**
     * 处理{@code restoreEntry}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chat/fs/restore-entry")
    public R<Map<String, Object>> restoreEntry(@RequestBody Map<String, Object> payload) {
        return R.ok(workspaceService.restore(text(payload, "trash_id"), text(payload, "original_path")));
    }

    /**
     * 处理{@code purgeEntry}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PostMapping("/chat/fs/purge-entry")
    public R<Map<String, Object>> purgeEntry(@RequestBody Map<String, Object> payload) {
        return R.ok(workspaceService.purge(text(payload, "trash_id"), text(payload, "original_path")));
    }

    /**
     * 处理{@code emptyTrash}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping("/chat/fs/empty-trash")
    public R<Map<String, Object>> emptyTrash() {
        return R.ok(workspaceService.emptyTrash());
    }

    /**
     * 处理{@code trashEntries}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/chat/fs/trash")
    public R<List<Map<String, Object>>> trashEntries() {
        return R.ok(workspaceService.trashEntries());
    }

    /**
     * 处理upload文件并返回对应结果。
     *
     * @param path {@code path}参数
     * @param file 文件参数
     * @return 处理结果
     */
    @PostMapping(value = "/chat/fs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> uploadFile(
        @RequestParam(required = false, defaultValue = "") String path,
        @RequestPart("file") MultipartFile file
    ) {
        return R.ok(workspaceService.upload(path, file));
    }

    /**
     * 查询{@code Files}列表。
     *
     * @param query 查询参数
     * @param path {@code path}参数
     * @return 处理结果
     */
    @GetMapping("/chat/fs/search")
    public R<List<Map<String, Object>>> searchFiles(
        @RequestParam String query,
        @RequestParam(required = false) String path
    ) {
        return R.ok(workspaceService.search(query, path));
    }

    /**
     * 处理{@code recentFiles}并返回对应结果。
     *
     * @param path {@code path}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/chat/fs/recent-files")
    public R<Map<String, Object>> recentFiles(
        @RequestParam(required = false) String path,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        List<Map<String, Object>> items = path == null || path.isBlank()
            ? workspaceService.storedRecent(limit)
            : workspaceService.recent(path, limit);
        return R.ok(Map.of("items", items));
    }

    /**
     * 更新{@code RecentFiles}。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PutMapping("/chat/fs/recent-files")
    public R<Map<String, Object>> updateRecentFiles(@RequestBody Map<String, Object> payload) {
        return R.ok(Map.of("items", workspaceService.updateRecent(payload == null ? null : payload.get("items"))));
    }

    /**
     * 处理浏览器Prefs并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/chat/fs/browser-prefs")
    public R<Map<String, Object>> browserPrefs() {
        return R.ok(workspaceService.browserPrefs());
    }

    /**
     * 更新浏览器Prefs。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @PutMapping("/chat/fs/browser-prefs")
    public R<Map<String, Object>> updateBrowserPrefs(@RequestBody Map<String, Object> payload) {
        return R.ok(workspaceService.updateBrowserPrefs(payload));
    }

    /**
     * 获取对话Bi数据集。
     *
     * @param dataSource 数据数据源参数
     * @param datasetName 名称
     * @return 处理结果
     */
    private DatasetView resolveChatBiDataset(String dataSource, String datasetName) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<Long, DataSourceView> sources = new LinkedHashMap<>();
        List<DatasetView> matches = new ArrayList<>();
        for (DatasetView dataset : dataCatalogService.listDatasets(200)) {
            if (datasetName != null && !datasetName.isBlank()
                && !matchesIdentifier(datasetName, dataset.datasetKey(), dataset.name())) {
                continue;
            }
            DataSourceView source = sources.computeIfAbsent(
                dataset.dataSourceId(), dataCatalogService::getSource
            );
            if (matchesIdentifier(
                dataSource, String.valueOf(source.id()), source.sourceKey(), source.name(),
                source.databaseName(), source.dbType()
            )) {
                matches.add(dataset);
            }
        }
        if (matches.isEmpty()) {
            throw new ServiceException("没有找到可访问的数据源或数据集", HttpStatus.NOT_FOUND);
        }
        if (matches.size() > 1) {
            throw new ServiceException("数据源匹配多个数据集，请提供 dataset_name", HttpStatus.BAD_REQUEST);
        }
        return matches.getFirst();
    }

    /**
     * 判断{@code Identifier}是否满足要求。
     *
     * @param expected {@code expected}参数
     * @param candidates {@code candidates}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean matchesIdentifier(String expected, String... candidates) {
        String normalized = expected.strip();
        for (String candidate : candidates) {
            if (candidate != null && normalized.equalsIgnoreCase(candidate.strip())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 处理{@code nhsSqlSuccess}并返回对应结果。
     *
     * @param data 数据参数
     * @return 处理结果
     */
    private Map<String, Object> nhsSqlSuccess(Map<String, Object> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", HttpStatus.SUCCESS);
        response.put("message", "success");
        response.put("data", data);
        response.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        response.put("trace_id", null);
        response.put("execution_mode", "local");
        return response;
    }

    /**
     * 获取智能体。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    private AgentView resolveAgent(Long agentId) {
        List<AgentView> allowed = agentService.allowed(100);
        if (agentId != null) {
            return allowed.stream().filter(item -> agentId.equals(item.id()) && item.publishedVersionId() != null)
                .findFirst().orElseThrow(() -> new ServiceException("Agent 不存在或无权使用", HttpStatus.FORBIDDEN));
        }
        return allowed.stream().filter(item -> item.publishedVersionId() != null).findFirst()
            .orElseThrow(() -> new ServiceException("没有可用的已发布 Agent", HttpStatus.CONFLICT));
    }

    /**
     * 处理会话并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> conversation(ConversationView value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation_id", value.id());
        result.put("title", value.title() == null ? "" : value.title());
        result.put("created_at", value.createdAt());
        result.put("updated_at", value.lastMessageAt());
        return result;
    }

    /**
     * 处理消息并返回对应结果。
     *
     * @param value {@code value}参数
     * @param events {@code events}参数
     * @return 处理结果
     */
    private Map<String, Object> message(ConversationMessageView value, List<ExecutionEventView> events) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id());
        result.put("role", value.role());
        result.put("content", value.content() == null ? "" : value.content());
        result.put("created_at", value.createdAt());
        result.put("trace_id", value.traceId() == null ? "" : value.traceId());
        if ("assistant".equals(value.role()) && value.traceId() != null) {
            String reasoning = traceReasoning(events.stream()
                .filter(event -> value.traceId().equals(event.traceId())).toList());
            result.put("reasoning_content", reasoning);
        }
        return result;
    }

    /**
     * 处理链路追踪Log并返回对应结果。
     *
     * @param trace 链路追踪参数
     * @return 处理结果
     */
    private Map<String, Object> traceLog(ConversationTrace trace) {
        List<Map<String, Object>> steps = new ArrayList<>(trace.events().size());
        for (int index = 0; index < trace.events().size(); index++) {
            steps.add(traceStep(trace.events().get(index), index + 1));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trace_id", trace.traceId());
        result.put("total_steps", steps.size());
        result.put("steps", steps);
        result.put("history", traceHistory(trace));
        return result;
    }

    /**
     * 处理链路追踪Step并返回对应结果。
     *
     * @param event 事件参数
     * @param stepNumber {@code stepNumber}参数
     * @return 处理结果
     */
    private Map<String, Object> traceStep(ExecutionEventView event, int stepNumber) {
        Map<String, Object> values = event.projection().isEmpty()
            ? event.payload() : event.projection();
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step_number", stepNumber);
        step.put("event_type", event.eventType());
        step.put("agent_name", firstText(values, "agent_name", "agentName", "agent", "name", "Agent"));
        step.put("model", firstValue(values, "model", "model_name", "modelName"));
        step.put("temperature", firstNumber(values, "temperature"));
        step.put("tool_name", firstValue(values, "tool_name", "toolName"));
        step.put("tool_input", firstMap(values, "tool_input", "toolInput", "arguments", "input"));
        step.put("tool_output", firstValue(values, "tool_output", "toolOutput", "result", "output", "data"));
        step.put("raw_log", null);
        step.put("execution_time_ms", firstNumber(
            values, "execution_time_ms", "executionTimeMs", "elapsed_ms", "duration_ms", "durationMs"
        ));
        step.put("status", event.eventStatus() == null ? "success" : event.eventStatus());
        step.put("error_message", firstValue(values, "error_message", "errorMessage", "error", "message"));
        step.put("prompt_tokens", integerValue(values, "prompt_tokens", "promptTokens"));
        step.put("completion_tokens", integerValue(values, "completion_tokens", "completionTokens"));
        step.put("total_tokens", integerValue(values, "total_tokens", "totalTokens"));
        step.put("span_id", firstValue(values, "span_id", "spanId"));
        step.put("parent_span_id", firstValue(values, "parent_span_id", "parentSpanId"));
        step.put("meta_info", values);
        step.put("timestamp", event.occurredAt());
        return step;
    }

    /**
     * 处理链路追踪历史记录并返回对应结果。
     *
     * @param trace 链路追踪参数
     * @return 处理结果
     */
    private Map<String, Object> traceHistory(ConversationTrace trace) {
        var turn = trace.turn();
        var messages = trace.messages();
        var userMessage = messages.stream().filter(item -> "user".equals(item.getRole())).findFirst().orElse(null);
        var assistantMessage = messages.stream().filter(item -> "assistant".equals(item.getRole()))
            .reduce((first, second) -> second).orElse(null);
        long elapsed = turn.getStartedAt() != null && turn.getFinishedAt() != null
            ? Duration.between(turn.getStartedAt(), turn.getFinishedAt()).toMillis() : 0;
        int promptTokens = messages.stream().mapToInt(item -> safeInt(item.getPromptTokens())).sum();
        int completionTokens = messages.stream().mapToInt(item -> safeInt(item.getCompletionTokens())).sum();
        int totalTokens = messages.stream().mapToInt(item -> safeInt(item.getTotalTokens())).sum();
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("id", turn.getId());
        history.put("trace_id", trace.traceId());
        history.put("agent_id", turn.getAgentId() == null ? "" : String.valueOf(turn.getAgentId()));
        history.put("conversation_id", String.valueOf(turn.getConversationId()));
        history.put("project_name", null);
        history.put("username", trace.username());
        history.put("query", userMessage == null ? null : userMessage.getContent());
        history.put("summary", assistantMessage == null ? null : assistantMessage.getContent());
        history.put("reasoning_content", traceReasoning(trace.events()));
        history.put("status", historyStatus(turn.getStatus()));
        history.put("agent_version", turn.getAgentVersionId() == null
            ? null : String.valueOf(turn.getAgentVersionId()));
        history.put("model_id", assistantMessage == null || assistantMessage.getModelId() == null
            ? null : String.valueOf(assistantMessage.getModelId()));
        history.put("execution_time_ms", elapsed);
        history.put("prompt_tokens", promptTokens);
        history.put("completion_tokens", completionTokens);
        history.put("total_tokens", totalTokens);
        history.put("turn_count", null);
        history.put("created_at", turn.getStartedAt());
        history.put("agent_name", null);
        history.put("agent_display_name", null);
        return history;
    }

    /**
     * 处理链路追踪Reasoning并返回对应结果。
     *
     * @param events {@code events}参数
     * @return 处理结果
     */
    private String traceReasoning(List<ExecutionEventView> events) {
        String value = events.stream()
            .filter(event -> "reasoning_content".equals(event.eventType())
                || "thinking_delta".equals(event.eventType()))
            .filter(event -> !Boolean.TRUE.equals(event.payload().get("redacted")))
            .map(event -> firstText(event.payload(), "content", "delta", "text", event.summary()))
            .filter(text -> text != null && !text.isBlank())
            .reduce("", String::concat);
        return value.isBlank() ? null : value;
    }

    /**
     * 处理历史记录Status并返回对应结果。
     *
     * @param status 目标状态
     * @return 处理结果
     */
    private String historyStatus(String status) {
        return "succeeded".equals(status) ? "success" : status;
    }

    /**
     * 处理{@code safeInt}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 处理{@code firstValue}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private Object firstValue(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 处理{@code firstNumber}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private Number firstNumber(Map<String, Object> values, String... keys) {
        Object value = firstValue(values, keys);
        return value instanceof Number number ? number : null;
    }

    /**
     * 处理{@code booleanValue}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param keys {@code keys}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean booleanValue(Map<String, Object> values, String... keys) {
        Object value = firstValue(values, keys);
        return value instanceof Boolean bool && bool;
    }

    /**
     * 处理{@code elapsedMillis}并返回对应结果。
     *
     * @param startedAt {@code startedAt}参数
     * @param finishedAt {@code finishedAt}参数
     * @return 处理结果
     */
    private double elapsedMillis(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return 0D;
        }
        return Duration.between(startedAt, finishedAt).toNanos() / 1_000_000D;
    }

    /**
     * 处理{@code integerValue}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private int integerValue(Map<String, Object> values, String... keys) {
        Number value = firstNumber(values, keys);
        return value == null ? 0 : value.intValue();
    }

    /**
     * 处理{@code firstMap}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> firstMap(Map<String, Object> values, String... keys) {
        Object value = firstValue(values, keys);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /**
     * 处理{@code firstText}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param keysAndFallback {@code keysAndFallback}参数
     * @return 处理结果
     */
    private String firstText(Map<String, Object> values, String... keysAndFallback) {
        for (int index = 0; index < keysAndFallback.length - 1; index++) {
            Object value = values.get(keysAndFallback[index]);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return keysAndFallback.length == 0 ? null : keysAndFallback[keysAndFallback.length - 1];
    }

    /**
     * 处理任务并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> task(TaskView value) {
        return Map.of("id", value.id(), "name", value.title(), "agent_id", value.currentVersionId(),
            "prompt", value.objective(), "status", value.status(), "user_id", value.ownerId(),
            "created_at", value.createdAt(), "updated_at", value.createdAt(), "source", "web");
    }

    /**
     * 执行{@code run}相关的处理流程。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> run(TaskRunView value) {
        return Map.of("id", value.id(), "trace_id", value.traceId(), "status", value.status(),
            "created_at", value.createdAt(), "started_at", value.startedAt() == null ? "" : value.startedAt(),
            "finished_at", value.finishedAt() == null ? "" : value.finishedAt());
    }

    /**
     * 处理{@code subscription}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> subscription(ReportSubscriptionView value) {
        ReportView report = reportService.get(value.reportId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", -value.id());
        result.put("subscription_id", value.id());
        result.put("task_type", "saved_report");
        result.put("name", report.name());
        result.put("user_id", report.ownerId());
        result.put("agent_id", "saved_report");
        result.put("agent_name", "黄金报表");
        result.put("source", "saved_report");
        result.put("status", "active".equals(value.status()) ? 1 : 0);
        result.put("last_run_at", value.lastRunAt());
        result.put("next_run_at", value.nextRunAt());
        result.put("created_at", value.createdAt());
        result.put("report_id", value.reportId());
        result.put("trigger_id", value.triggerId());
        return result;
    }

    /**
     * 判断{@code matches}是否满足要求。
     *
     * @param item {@code item}参数
     * @param needle {@code needle}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean matches(TaskExecutionProjection item, String needle) {
        return item.task().title().toLowerCase().contains(needle)
            || (item.run().traceId() != null && item.run().traceId().toLowerCase().contains(needle))
            || (item.run().errorSummary() != null && item.run().errorSummary().toLowerCase().contains(needle));
    }

    /**
     * 处理执行历史记录Item并返回对应结果。
     *
     * @param item {@code item}参数
     * @return 处理结果
     */
    private Map<String, Object> executionHistoryItem(TaskExecutionProjection item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.run().id());
        result.put("task_id", item.task().id());
        result.put("task_name", item.task().title());
        result.put("user_id", item.task().ownerId());
        result.put("trace_id", item.run().traceId());
        result.put("status", "succeeded".equals(item.run().status()) ? "success" : item.run().status());
        result.put("error", item.run().errorSummary());
        result.put("started_at", item.run().startedAt());
        result.put("finished_at", item.run().finishedAt());
        result.put("created_at", item.run().createdAt());
        return result;
    }

    /**
     * 封装任务执行Projection相关的不可变数据。
     */
    private record TaskExecutionProjection(TaskView task, TaskRunView run) {
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private Long number(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; }
    }

    /**
     * 校验{@code dNumber}，并在条件不满足时终止处理。
     *
     * @param payload {@code payload}参数
     * @param key {@code key}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long requiredNumber(Map<String, Object> payload, String key, String label) {
        Long value = number(payload, key);
        if (value == null || value <= 0) {
            throw new ServiceException(label + "不能为空", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private Integer integer(Map<String, Object> payload, String key) {
        Long value = number(payload, key);
        if (value == null) {
            return null;
        }
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new ServiceException(key + "超出范围", HttpStatus.BAD_REQUEST);
        }
        return value.intValue();
    }

    /**
     * 处理{@code longList}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<Long> longList(Object value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : raw) {
            Long parsed;
            if (item instanceof Number number) {
                parsed = number.longValue();
            } else {
                try {
                    parsed = Long.valueOf(String.valueOf(item));
                } catch (NumberFormatException exception) {
                    throw new ServiceException("ID列表包含非法值", HttpStatus.BAD_REQUEST);
                }
            }
            if (parsed <= 0) {
                throw new ServiceException("ID必须为正数", HttpStatus.BAD_REQUEST);
            }
            result.add(parsed);
        }
        return result.stream().distinct().toList();
    }

    /**
     * 处理资源Map并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, List<Long>> resourceMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, List<Long>> result = new LinkedHashMap<>();
        raw.forEach((key, ids) -> result.put(String.valueOf(key), longList(ids)));
        return result;
    }

    /**
     * 处理{@code resumeCursor}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @param lastEventId 资源标识
     * @return 处理结果
     */
    private long resumeCursor(Map<String, Object> payload, String lastEventId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Long payloadCursor = number(payload, "cursor");
        if (payloadCursor == null) {
            payloadCursor = number(payload, "after_cursor");
        }
        if (payloadCursor != null) {
            if (payloadCursor < 0) {
                throw new ServiceException("事件游标不能为负数", HttpStatus.BAD_REQUEST);
            }
            return payloadCursor;
        }
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            long value = Long.parseLong(lastEventId.strip());
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ServiceException("Last-Event-ID 无效", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 判断{@code Input}是否满足要求。
     *
     * @param payload {@code payload}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean hasInput(Map<String, Object> payload) {
        if (text(payload, "input") != null) {
            return true;
        }
        Object messages = payload == null ? null : payload.get("messages");
        return messages instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 处理parse审批Id并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private long parseApprovalId(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.startsWith("approval-")) {
            normalized = normalized.substring("approval-".length());
        }
        try {
            long id = Long.parseLong(normalized);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new ServiceException("permission_request_id无效", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理rejected审批Stream并返回对应结果。
     *
     * @param decision {@code decision}参数
     * @return 处理结果
     */
    private SseEmitter rejectedApprovalStream(ApprovalDecisionResult decision) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        Thread.ofVirtual().name("nhs-approval-rejected").start(() -> {
            try {
                emitter.send(SseEmitter.event()
                    .data(Map.of(
                        "type", "permission_result",
                        "permission_request_id", decision.approval().id(),
                        "status", decision.approval().status(),
                        "run_id", decision.approval().runId(),
                        "confirmed", false
                    )));
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private String text(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? null : String.valueOf(value).strip();
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param payload {@code payload}参数
     * @param key {@code key}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String required(Map<String, Object> payload, String key, String label) {
        String value = text(payload, key);
        if (value == null || value.isBlank()) throw new ServiceException(label + "不能为空", HttpStatus.BAD_REQUEST);
        return value;
    }

    /**
     * 处理{@code textOr}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @param key {@code key}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String textOr(Map<String, Object> payload, String key, String fallback) {
        String value = text(payload, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 处理{@code input}并返回对应结果。
     *
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    @SuppressWarnings("unchecked")
    private String input(Map<String, Object> payload) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String input = text(payload, "input");
        if (input != null && !input.isBlank()) return input;
        Object messages = payload == null ? null : payload.get("messages");
        if (messages instanceof List<?> list && !list.isEmpty()) {
            Object last = list.get(list.size() - 1);
            if (last instanceof Map<?, ?> map) {
                Object content = map.get("content");
                if (content != null) return String.valueOf(content);
            }
        }
        throw new ServiceException("消息不能为空", HttpStatus.BAD_REQUEST);
    }
}
