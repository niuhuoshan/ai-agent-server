package group.aitools.nhs.platform.audit.service;

import group.aitools.nhs.runtime.spi.RuntimeSecretScrubber;
import group.aitools.nhs.platform.audit.domain.AgentAuditEvent;
import group.aitools.nhs.platform.audit.mapper.AgentAuditQueryMapper;
import group.aitools.nhs.platform.audit.mapper.AuditStatisticsRow;
import group.aitools.nhs.platform.audit.web.AuditEventDetailView;
import group.aitools.nhs.platform.audit.web.AuditEventView;
import group.aitools.nhs.platform.audit.web.AuditExportedFile;
import group.aitools.nhs.platform.audit.web.AuditFeatureView;
import group.aitools.nhs.platform.audit.web.AuditStatisticsView;
import group.aitools.nhs.platform.audit.web.AuditTraceSpansView;
import group.aitools.nhs.platform.audit.web.AuditTraceStepView;
import group.aitools.nhs.platform.audit.web.AuditTraceView;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.service.ExecutionTraceAggregationService;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 负责审计Operations相关的业务编排与领域规则处理。
 * Detail, aggregate, export and trace operations for the administrator audit console. */
@Service
public class AuditOperationsApplicationService {

    private static final int MAX_EXPORT_ROWS = 10_000;
    private static final int MAX_TRACE_STEPS = 1_000;
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final Set<String> ACTOR_TYPES = Set.of(
        "user", "service_account", "application", "agent", "system"
    );
    private static final Set<String> DECISIONS = Set.of(
        "allow", "deny", "approval_required", "success", "failure"
    );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final AgentAuditQueryMapper mapper;
    private final AgentExecutionEventMapper executionEventMapper;
    private final AgentConversationMapper conversationMapper;
    private final TaskQueryService taskQueryService;
    private final JsonMapper jsonMapper;
    private final ExecutionTraceAggregationService traceAggregationService;

    /**
     * 创建 {@code AuditOperationsApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param mapper {@code mapper}参数
     * @param executionEventMapper 执行事件Mapper参数
     * @param conversationMapper 会话Mapper参数
     * @param taskQueryService 任务查询Service参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param traceAggregationService 链路追踪AggregationService参数
     */
    public AuditOperationsApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        AgentAuditQueryMapper mapper,
        AgentExecutionEventMapper executionEventMapper,
        AgentConversationMapper conversationMapper,
        TaskQueryService taskQueryService,
        JsonMapper jsonMapper,
        ExecutionTraceAggregationService traceAggregationService
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.mapper = mapper;
        this.executionEventMapper = executionEventMapper;
        this.conversationMapper = conversationMapper;
        this.taskQueryService = taskQueryService;
        this.jsonMapper = jsonMapper;
        this.traceAggregationService = traceAggregationService;
    }

    /**
     * 处理{@code features}并返回对应结果。
     *
     * @return 处理结果
     */
    public AuditFeatureView features() {
        authorize("list");
        return new AuditFeatureView(
            List.copyOf(mapper.distinctActorTypes()),
            List.copyOf(mapper.distinctActions()),
            List.copyOf(mapper.distinctResourceTypes()),
            List.copyOf(mapper.distinctDecisions())
        );
    }

    /**
     * 处理统计并返回对应结果。
     *
     * @param filter {@code filter}参数
     * @return 处理结果
     */
    public AuditStatisticsView statistics(AuditFilter filter) {
        authorize("list");
        AuditFilter normalized = normalize(filter, 100);
        AuditStatisticsRow row = mapper.statistics(
            normalized.actorType(), normalized.actorId(), normalized.action(), normalized.resourceType(),
            normalized.resourceId(), normalized.taskId(), normalized.runId(), normalized.decision(),
            normalized.createdFrom(), normalized.createdTo()
        );
        return statisticsView(row);
    }

    /**
     * 处理{@code detail}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    public AuditEventDetailView detail(Long id) {
        authorize("view");
        if (id == null || id <= 0) {
            throw new ServiceException("审计事件 ID 无效", HttpStatus.BAD_REQUEST);
        }
        AgentAuditEvent event = mapper.selectById(id);
        if (event == null) {
            throw new ServiceException("审计事件不存在", HttpStatus.NOT_FOUND);
        }
        authorizeAuditEventScope(event);
        return new AuditEventDetailView(
            event.getId(), event.getTraceId(), event.getActorType(), event.getActorId(), event.getAction(),
            event.getResourceType(), event.getResourceId(), event.getTaskId(), event.getRunId(),
            bounded(event.getPermissionProfileVersion(), 128), event.getDecision(),
            bounded(event.getDecisionReason(), 1000), safeJson(event.getDataScopeJson()),
            safeText(event.getRequestSummary(), 8_192), safeText(event.getResultSummary(), 8_192),
            bounded(event.getIpAddress(), 128), bounded(event.getUserAgent(), 1_024),
            safeJson(event.getMetadataJson()), event.getCreatedAt()
        );
    }

    /**
     * 处理导出并返回对应结果。
     *
     * @param filter {@code filter}参数
     * @param requestedFormat {@code requestedFormat}参数
     * @return 处理结果
     */
    public AuditExportedFile export(AuditFilter filter, String requestedFormat) {
        authorize("export");
        String format = requestedFormat == null ? "csv" : requestedFormat.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("csv", "json").contains(format)) {
            throw new ServiceException("审计导出仅支持 csv 或 json", HttpStatus.BAD_REQUEST);
        }
        AuditFilter normalized = normalize(filter, MAX_EXPORT_ROWS);
        List<AgentAuditEvent> rows = mapper.export(
            normalized.actorType(), normalized.actorId(), normalized.action(), normalized.resourceType(),
            normalized.resourceId(), normalized.taskId(), normalized.runId(), normalized.decision(),
            normalized.createdFrom(), normalized.createdTo(), MAX_EXPORT_ROWS
        );
        byte[] content = "json".equals(format) ? jsonExport(rows) : csvExport(rows);
        String stamp = String.valueOf(System.currentTimeMillis());
        return new AuditExportedFile(
            "audit-events-" + stamp + "." + format,
            "csv".equals(format) ? "text/csv;charset=UTF-8" : "application/json;charset=UTF-8",
            content
        );
    }

    /**
     * 处理链路追踪并返回对应结果。
     *
     * @param rawTraceId 资源标识
     * @return 处理结果
     */
    public AuditTraceView trace(String rawTraceId) {
        List<AuditTraceStepView> steps = traceSteps(rawTraceId);
        return new AuditTraceView(normalizeTraceId(rawTraceId), steps.size(), steps);
    }

    /**
     * 处理{@code spans}并返回对应结果。
     *
     * @param rawTraceId 资源标识
     * @return 处理结果
     */
    public AuditTraceSpansView spans(String rawTraceId) {
        List<AuditTraceStepView> steps = traceSteps(rawTraceId);
        return new AuditTraceSpansView(normalizeTraceId(rawTraceId), steps);
    }

    /**
     * 查询{@code search}列表。
     *
     * @param filter {@code filter}参数
     * @param limit 数量上限
     * @param beforeId 资源标识
     * @return 符合条件的数据集合
     */
    public List<AuditEventView> search(AuditFilter filter, int limit, Long beforeId) {
        authorize("list");
        AuditFilter normalized = normalize(filter, limit);
        return mapper.search(
            normalized.actorType(), normalized.actorId(), normalized.action(), normalized.resourceType(),
            normalized.resourceId(), normalized.taskId(), normalized.runId(), normalized.decision(),
            normalized.createdFrom(), normalized.createdTo(), beforeId, normalized.limit()
        ).stream().map(AuditEventView::from).toList();
    }

    /**
     * 处理{@code count}并返回对应结果。
     *
     * @param filter {@code filter}参数
     * @return 处理结果
     */
    public long count(AuditFilter filter) {
        authorize("list");
        AuditFilter normalized = normalize(filter, 100);
        return mapper.count(
            normalized.actorType(), normalized.actorId(), normalized.action(), normalized.resourceType(),
            normalized.resourceId(), normalized.taskId(), normalized.runId(), normalized.decision(),
            normalized.createdFrom(), normalized.createdTo()
        );
    }

    /**
     * 处理链路追踪Steps并返回对应结果。
     *
     * @param rawTraceId 资源标识
     * @return 符合条件的数据集合
     */
    private List<AuditTraceStepView> traceSteps(String rawTraceId) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        authorize("view");
        String traceId = normalizeTraceId(rawTraceId);
        List<AgentExecutionEvent> rows = mapper.selectTraceEvents(traceId, MAX_TRACE_STEPS + 1);
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException("执行链路不存在", HttpStatus.NOT_FOUND);
        }
        Set<String> authorizedScopes = new HashSet<>();
        for (AgentExecutionEvent row : rows) {
            Long taskId = row.getRunId() == null
                ? null : executionEventMapper.selectTaskIdForRun(row.getRunId());
            String scopeKey = String.valueOf(taskId) + "|"
                + String.valueOf(row.getRunId()) + "|"
                + String.valueOf(row.getConversationId());
            if (authorizedScopes.add(scopeKey)) {
                authorizeEventScope(taskId, row.getRunId(), row.getConversationId());
            }
        }
        if (rows.size() > MAX_TRACE_STEPS) {
            throw new ServiceException("执行链路步骤超过1000条限制", 413);
        }
        List<ExecutionEventView> events = rows.stream()
            .map(event -> ExecutionEventView.forTrace(event, jsonMapper))
            .toList();
        List<ExecutionEventView> aggregated = traceAggregationService.aggregate(events);
        List<AuditTraceStepView> steps = new ArrayList<>(aggregated.size());
        for (int index = 0; index < aggregated.size(); index++) {
            steps.add(traceStep(aggregated.get(index), index + 1));
        }
        return List.copyOf(steps);
    }

    /**
     * 处理链路追踪Step并返回对应结果。
     *
     * @param event 事件参数
     * @param stepNumber {@code stepNumber}参数
     * @return 处理结果
     */
    private AuditTraceStepView traceStep(ExecutionEventView event, int stepNumber) {
        Map<String, Object> values = event.projection().isEmpty() ? event.payload() : event.projection();
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String key : List.of(
            "toolState", "projectionTruncated", "toolInputTruncated", "toolOutputTruncated"
        )) {
            if (values.containsKey(key)) {
                metadata.put(key, RuntimeSecretScrubber.sanitizeValue(key, values.get(key)));
            }
        }
        return new AuditTraceStepView(
            stepNumber, event.eventId(), event.conversationId(), event.runId(), event.stepId(),
            event.cursor(), event.eventType(), event.eventStatus(), event.sensitiveLevel(),
            text(values, "agentName", "agent_name", "agent"), text(values, "model", "modelName", "model_name"),
            text(values, "toolName", "tool_name", "name"), safeText(event.summary(), 512),
            number(values, "executionTimeMs", "durationMs", "duration_ms", "elapsedMs"),
            integer(values, "promptTokens", "prompt_tokens"), integer(values, "completionTokens", "completion_tokens"),
            integer(values, "totalTokens", "total_tokens"), text(values, "spanId", "span_id"),
            text(values, "parentSpanId", "parent_span_id"), Collections.unmodifiableMap(metadata), event.occurredAt()
        );
    }

    /**
     * 处理统计View并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private AuditStatisticsView statisticsView(AuditStatisticsRow row) {
        if (row == null) {
            return new AuditStatisticsView(0, 0, 0, 0, 0, 0, 0, 0);
        }
        return new AuditStatisticsView(
            row.getTotal(), row.getAllowCount(), row.getDenyCount(), row.getApprovalRequiredCount(),
            row.getSuccessCount(), row.getFailureCount(), row.getDistinctActors(), row.getDistinctTraces()
        );
    }

    /**
     * 处理json导出并返回对应结果。
     *
     * @param rows {@code rows}参数
     * @return 处理结果
     */
    private byte[] jsonExport(List<AgentAuditEvent> rows) {
        List<AuditEventView> safe = rows.stream().map(AuditEventView::from).toList();
        try {
            return jsonMapper.writeValueAsString(safe).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            throw new ServiceException("审计导出序列化失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理csv导出并返回对应结果。
     *
     * @param rows {@code rows}参数
     * @return 处理结果
     */
    private byte[] csvExport(List<AgentAuditEvent> rows) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("id,trace_id,actor_type,actor_id,action,resource_type,resource_id,task_id,run_id,decision,decision_reason,created_at\n");
        for (AgentAuditEvent row : rows) {
            csv.append(csv(row.getId())).append(',').append(csv(row.getTraceId())).append(',')
                .append(csv(row.getActorType())).append(',').append(csv(row.getActorId())).append(',')
                .append(csv(row.getAction())).append(',').append(csv(row.getResourceType())).append(',')
                .append(csv(row.getResourceId())).append(',').append(csv(row.getTaskId())).append(',')
                .append(csv(row.getRunId())).append(',').append(csv(row.getDecision())).append(',')
                .append(csv(safeText(row.getDecisionReason(), 1000))).append(',').append(csv(row.getCreatedAt()))
                .append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 处理{@code csv}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String csv(Object value) {
        if (value == null) return "";
        String text = RuntimeSecretScrubber.scrubText(String.valueOf(value));
        return '"' + text.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + '"';
    }

    /**
     * 处理{@code safeJson}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> safeJson(String value) {
        if (value == null || value.isBlank()) return Map.of();
        String bounded = value.length() > 64 * 1024 ? value.substring(0, 64 * 1024) : value;
        try {
            Map<String, Object> parsed = jsonMapper.readValue(bounded, MAP_TYPE);
            return parsed == null ? Map.of() : RuntimeSecretScrubber.sanitizeMap(parsed);
        } catch (RuntimeException exception) {
            return Map.of("redacted", true, "invalidJson", true);
        }
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param input {@code input}参数
     * @param maxLimit 数量上限
     * @return 处理结果
     */
    private AuditFilter normalize(AuditFilter input, int maxLimit) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        AuditFilter value = input == null ? AuditFilter.empty() : input;
        String actorType = token(value.actorType(), 32, "主体类型");
        if (actorType != null && !ACTOR_TYPES.contains(actorType)) throw invalid("主体类型");
        String decision = token(value.decision(), 32, "决策");
        if (decision != null && !DECISIONS.contains(decision)) throw invalid("决策");
        String action = token(value.action(), 64, "操作");
        String resourceType = token(value.resourceType(), 32, "资源类型");
        LocalDateTime to = value.createdTo() == null ? LocalDateTime.now().plusSeconds(1) : value.createdTo();
        LocalDateTime from = value.createdFrom() == null ? to.minusDays(30) : value.createdFrom();
        if (!from.isBefore(to)) throw new ServiceException("审计开始时间必须早于结束时间", HttpStatus.BAD_REQUEST);
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new ServiceException("单次审计检索时间范围不能超过90天", HttpStatus.BAD_REQUEST);
        }
        int limit = Math.max(1, Math.min(value.limit() <= 0 ? 50 : value.limit(), maxLimit));
        return new AuditFilter(actorType, value.actorId(), action, resourceType, value.resourceId(), value.taskId(),
            value.runId(), decision, from, to, limit);
    }

    /**
     * 将输入数据转换为{@code ken}。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param field {@code field}参数
     * @return 处理结果
     */
    private String token(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > maxLength || normalized.indexOf('\0') >= 0
            || !normalized.matches("[a-z0-9_.:-]+")) throw invalid(field);
        return normalized;
    }

    /**
     * 处理{@code invalid}并返回对应结果。
     *
     * @param field {@code field}参数
     * @return 处理结果
     */
    private ServiceException invalid(String field) {
        return new ServiceException(field + "无效", HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理{@code authorize}相关逻辑。
     *
     * @param action {@code action}参数
     */
    private void authorize(String action) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "audit", null, "audit-events", action, ResourceState.ACTIVE, true, Set.of()
        ));
    }

    /**
 * 处理authorize事件范围相关逻辑。
 *
     * Replays the resource boundary behind an audit/trace record. Audit menu
     * permission alone must not expose a private conversation or restricted
     * task run. Pure platform events without a task/conversation keep the
     * existing platform-audit authorization decision.
     */
    private void authorizeEventScope(Long taskId, Long runId, Long conversationId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (taskId == null && runId != null) {
            taskId = executionEventMapper.selectTaskIdForRun(runId);
        }
        if (taskId != null) {
            taskQueryService.get(taskId);
            return;
        }
        if (runId != null) {
            throw new ServiceException("任务运行不存在", HttpStatus.NOT_FOUND);
        }
        if (conversationId == null) {
            return;
        }
        AgentConversation conversation = principal.hasRole(PlatformRole.PLATFORM_ADMIN)
            ? conversationMapper.selectById(conversationId)
            : conversationMapper.selectOwnedConversation(conversationId, principal.id());
        if (conversation == null) {
            throw new ServiceException("执行链路不存在", HttpStatus.NOT_FOUND);
        }
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "conversation", conversationId, null, "view", ResourceState.ACTIVE, true, Set.of(), null
        ));
    }

    /**
     * 处理authorize审计事件范围相关逻辑。
     *
     * @param event 事件参数
     */
    private void authorizeAuditEventScope(AgentAuditEvent event) {
        if (event.getTaskId() != null || event.getRunId() != null) {
            authorizeEventScope(event.getTaskId(), event.getRunId(), null);
            return;
        }
        String traceId = event.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        List<AgentExecutionEvent> rows = mapper.selectTraceEvents(traceId, 1);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        AgentExecutionEvent first = rows.getFirst();
        Long taskId = first.getRunId() == null
            ? null : executionEventMapper.selectTaskIdForRun(first.getRunId());
        authorizeEventScope(taskId, first.getRunId(), first.getConversationId());
    }

    /**
     * 处理normalize链路追踪Id并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeTraceId(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 64
            || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new ServiceException("Trace ID无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private String text(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return safeText(String.valueOf(value), 255);
        }
        return null;
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private Integer integer(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof Number number) return number.intValue();
        }
        return null;
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private Double number(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof Number number) return number.doubleValue();
        }
        return null;
    }

    /**
     * 处理{@code safeText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String safeText(String value, int maxLength) {
        if (value == null) return null;
        String normalized = RuntimeSecretScrubber.scrubText(value.replace('\0', ' '));
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String bounded(String value, int maxLength) {
        return value == null ? null : value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    /**
     * 封装审计相关的不可变数据。
     */
    public record AuditFilter(
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
        /**
         * 处理{@code empty}并返回对应结果。
         *
         * @return 处理结果
         */
        public static AuditFilter empty() {
            return new AuditFilter(null, null, null, null, null, null, null, null, null, null, 50);
        }
    }
}
