package group.aitools.nhs.platform.debug.service;

import group.aitools.nhs.platform.agent.domain.AgentDefinition;
import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionMapper;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionVersionMapper;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.debug.mapper.AgentDebugRunMapper;
import group.aitools.nhs.platform.debug.persistence.row.AgentDebugRunRow;
import group.aitools.nhs.platform.debug.web.AgentDebugMetricsView;
import group.aitools.nhs.platform.debug.web.AgentDebugOptionView;
import group.aitools.nhs.platform.debug.web.AgentDebugRunDetailView;
import group.aitools.nhs.platform.debug.web.AgentDebugRunSummaryView;
import group.aitools.nhs.platform.debug.web.AgentDebugVersionOptionView;
import group.aitools.nhs.platform.debug.web.CreateAgentDebugRunRequest;
import group.aitools.nhs.platform.debug.web.RetryAgentDebugRunRequest;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService.EventStreamReader;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.execution.web.CreateTaskRunRequest;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.execution.web.RetryTaskRunRequest;
import group.aitools.nhs.platform.execution.web.RunStepView;
import group.aitools.nhs.platform.execution.web.TaskRunActionResult;
import group.aitools.nhs.platform.execution.web.TaskRunView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.AuthorizationService;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.web.CreateTaskRequest;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 负责智能体Debug相关的业务编排与领域规则处理。
 * Full debugger facade backed by the same immutable task runtime as production work. */
@Service
public class AgentDebugApplicationService {

    private static final int EVENT_PAGE_SIZE = 500;
    private static final int MAX_DETAIL_EVENTS = 10_000;
    private static final int MAX_OUTPUT_CHARS = 1024 * 1024;
    private static final Set<String> EXECUTABLE_VERSION_STATUSES = Set.of("published", "archived");
    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of(
        "succeeded", "failed", "cancelled", "expired"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationService authorizationService;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final AgentDefinitionMapper definitionMapper;
    private final AgentDefinitionVersionMapper versionMapper;
    private final AgentDebugRunMapper debugRunMapper;
    private final AgentExecutionEventMapper eventMapper;
    private final TaskApplicationService taskService;
    private final TaskRunApplicationService runService;
    private final AgentDebugAuditService auditService;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code AgentDebugApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationService 授权Service参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param definitionMapper 定义Mapper参数
     * @param versionMapper 版本Mapper参数
     * @param debugRunMapper {@code debugRunMapper}参数
     * @param eventMapper 事件Mapper参数
     * @param taskService 任务Service参数
     * @param runService {@code runService}参数
     * @param auditService 审计Service参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public AgentDebugApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationService authorizationService,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        AgentDefinitionMapper definitionMapper,
        AgentDefinitionVersionMapper versionMapper,
        AgentDebugRunMapper debugRunMapper,
        AgentExecutionEventMapper eventMapper,
        TaskApplicationService taskService,
        TaskRunApplicationService runService,
        AgentDebugAuditService auditService,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationService = authorizationService;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.debugRunMapper = debugRunMapper;
        this.eventMapper = eventMapper;
        this.taskService = taskService;
        this.runService = runService;
        this.auditService = auditService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code options}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    public List<AgentDebugOptionView> options() {
        CurrentPrincipal principal = humanPrincipal();
        return audited(principal, "debug_options", null, null, "options", () ->
            definitionMapper.selectActiveCandidates(200).stream()
                .map(definition -> option(principal, definition))
                .filter(option -> !option.versions().isEmpty())
                .toList()
        );
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<AgentDebugRunSummaryView> list(int limit) {
        CurrentPrincipal principal = humanPrincipal();
        return audited(principal, "debug_list", null, null, "limit=" + limit, () ->
            debugRunMapper.selectOwnedList(principal.id(), limit).stream()
                .map(this::summary)
                .toList()
        );
    }

    /**
     * 获取{@code get}。
     *
     * @param debugRunId 资源标识
     * @return 处理结果
     */
    public AgentDebugRunDetailView get(Long debugRunId) {
        CurrentPrincipal principal = humanPrincipal();
        return auditedOwned(principal, "debug_view", debugRunId, this::detail);
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentDebugRunDetailView create(CreateAgentDebugRunRequest request) {
        CurrentPrincipal principal = humanPrincipal();
        String input = normalizedInput(request.input());
        String inputHash = ContentHashing.sha256(input);
        String idempotencyKey = request.idempotencyKey().strip();
        String auditSummary = "agentId=" + request.agentId()
            + ",versionId=" + request.agentVersionId() + ",inputSha256=" + inputHash;
        return audited(principal, "debug_create", null, null, auditSummary, () -> {
            AgentDebugRunRow existing = debugRunMapper.selectOwnedByIdempotencyKey(
                principal.id(), idempotencyKey
            );
            if (existing != null) {
                requireSameCreate(existing, request.agentId(), request.agentVersionId(), inputHash);
                return detail(existing);
            }

            AgentDefinition definition = requireExecutableVersion(
                principal, request.agentId(), request.agentVersionId()
            );
            String taskKey = "debug:" + idempotencyKey;
            TaskMutationResult task = taskService.create(debugTaskRequest(
                taskKey, definition, request.agentVersionId(), input, inputHash
            ));
            TaskRunActionResult created = runService.create(
                task.task().id(), new CreateTaskRunRequest("debug-run:" + idempotencyKey, input)
            );

            AgentDebugRunRow row = debugRow(
                principal.id(), idempotencyKey, request.agentId(), request.agentVersionId(),
                task.task().id(), created.run().id(), null, input, inputHash
            );
            insertOrReplay(row, request.agentId(), request.agentVersionId(), inputHash);
            runService.start(row.getTaskId(), row.getRunId());
            return detail(requireOwned(principal, row.getId()));
        });
    }

    /**
     * 处理{@code stop}并返回对应结果。
     *
     * @param debugRunId 资源标识
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentDebugRunDetailView stop(Long debugRunId, String reason) {
        CurrentPrincipal principal = humanPrincipal();
        return auditedOwned(principal, "debug_stop", debugRunId, row -> {
                TaskRunView run = runService.get(row.getTaskId(), row.getRunId());
                if (Set.of("preparing", "running").contains(run.status())) {
                    runService.pause(row.getTaskId(), row.getRunId(), reason);
                } else if (!TERMINAL_RUN_STATUSES.contains(run.status())
                    && !"paused".equals(run.status())) {
                    runService.cancel(row.getTaskId(), row.getRunId(), reason);
                }
                return detail(row);
            }
        );
    }

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param debugRunId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentDebugRunDetailView resume(Long debugRunId) {
        CurrentPrincipal principal = humanPrincipal();
        return auditedOwned(principal, "debug_resume", debugRunId, row -> {
                runService.resume(row.getTaskId(), row.getRunId());
                return detail(row);
            }
        );
    }

    /**
     * 处理{@code retry}并返回对应结果。
     *
     * @param debugRunId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentDebugRunDetailView retry(
        Long debugRunId,
        RetryAgentDebugRunRequest request
    ) {
        CurrentPrincipal principal = humanPrincipal();
        String idempotencyKey = request.idempotencyKey().strip();
        return auditedOwned(principal, "debug_retry", debugRunId, parent -> {
                AgentDebugRunRow existing = debugRunMapper.selectOwnedByIdempotencyKey(
                    principal.id(), idempotencyKey
                );
                if (existing != null) {
                    if (!parent.getId().equals(existing.getParentDebugRunId())) {
                        throw conflict("同一调试幂等键不能用于不同重试来源");
                    }
                    return detail(existing);
                }
                TaskRunActionResult retried = runService.retry(
                    parent.getTaskId(), parent.getRunId(),
                    new RetryTaskRunRequest("debug-retry:" + idempotencyKey, true)
                );
                AgentDebugRunRow row = debugRow(
                    principal.id(), idempotencyKey, parent.getAgentId(),
                    parent.getAgentVersionId(), parent.getTaskId(), retried.run().id(),
                    parent.getId(), parent.getInputText(), parent.getInputSha256()
                );
                insertOrReplay(
                    row, parent.getAgentId(), parent.getAgentVersionId(), parent.getInputSha256()
                );
                return detail(requireOwned(principal, row.getId()));
            }
        );
    }

    /**
     * 处理{@code events}并返回对应结果。
     *
     * @param debugRunId 资源标识
     * @param cursor {@code cursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ExecutionEventView> events(Long debugRunId, long cursor, int limit) {
        CurrentPrincipal principal = humanPrincipal();
        return auditedOwned(principal, "debug_replay", debugRunId, row -> {
                runService.get(row.getTaskId(), row.getRunId());
                return eventViews(row, cursor, limit);
            }
        );
    }

    /**
     * 处理事件Reader并返回对应结果。
     *
     * @param debugRunId 资源标识
     * @return 处理结果
     */
    public EventStreamReader eventReader(Long debugRunId) {
        CurrentPrincipal principal = humanPrincipal();
        return auditedOwned(principal, "debug_stream", debugRunId, row -> {
                runService.get(row.getTaskId(), row.getRunId());
                return (cursor, limit) -> eventViews(row, cursor, limit);
            }
        );
    }

    /**
     * 处理{@code option}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param definition 定义参数
     * @return 处理结果
     */
    private AgentDebugOptionView option(CurrentPrincipal principal, AgentDefinition definition) {
        List<AgentDebugVersionOptionView> versions = versionMapper.selectVersions(definition.getId())
            .stream()
            .filter(version -> version.getPublishedAt() != null)
            .filter(version -> EXECUTABLE_VERSION_STATUSES.contains(version.getStatus()))
            .filter(version -> authorizationService.authorize(
                principal,
                new PermissionContext(
                    "agent_version", version.getId(), definition.getAgentKey(), "use",
                    ResourceState.ACTIVE, true, Set.of(), null
                )
            ).allowed())
            .map(version -> new AgentDebugVersionOptionView(
                version.getId(), version.getVersionNo(), version.getStatus(), version.getModelId(),
                version.getContentHash(), version.getPublishedAt()
            ))
            .toList();
        return new AgentDebugOptionView(
            definition.getId(), definition.getAgentKey(), definition.getName(),
            definition.getDescription(), definition.getAvatarUrl(),
            Boolean.TRUE.equals(definition.getIsDefault()), definition.getPublishedVersionId(),
            versions
        );
    }

    /**
     * 校验Executable版本，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    private AgentDefinition requireExecutableVersion(
        CurrentPrincipal principal,
        Long agentId,
        Long versionId
    ) {
        AgentDefinition definition = definitionMapper.selectDefinitionById(agentId);
        AgentDefinitionVersion version = versionMapper.selectVersion(agentId, versionId);
        if (definition == null || version == null) {
            throw notFound("Agent或版本不存在");
        }
        if (!"active".equals(definition.getStatus())
            || version.getPublishedAt() == null
            || !EXECUTABLE_VERSION_STATUSES.contains(version.getStatus())) {
            throw conflict("调试只能使用活动Agent曾经发布的不可变版本");
        }
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "agent_version", versionId, definition.getAgentKey(), "use",
            ResourceState.ACTIVE, true, Set.of(), null
        ));
        return definition;
    }

    /**
     * 处理debug任务Request并返回对应结果。
     *
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param definition 定义参数
     * @param versionId 资源标识
     * @param input {@code input}参数
     * @param inputHash {@code inputHash}参数
     * @return 处理结果
     */
    private CreateTaskRequest debugTaskRequest(
        String idempotencyKey,
        AgentDefinition definition,
        Long versionId,
        String input,
        String inputHash
    ) {
        String title = "[调试] " + definition.getName();
        return new CreateTaskRequest(
            idempotencyKey,
            title.length() <= 255 ? title : title.substring(0, 255),
            input,
            "Agent Debug / Playground 持久化运行",
            null,
            versionId,
            null,
            "restricted",
            "general",
            "single_agent",
            "L0_chat",
            "R1",
            "human",
            0,
            0,
            null,
            Map.of("surface", "agent_debug"),
            List.of(),
            Map.of(),
            Map.of("inputSha256", inputHash),
            Map.of(),
            Map.of("source", "agent_debug", "agentKey", definition.getAgentKey()),
            List.of("agent-debug")
        );
    }

    /**
     * 处理{@code debugRow}并返回对应结果。
     *
     * @param ownerId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param parentDebugRunId 资源标识
     * @param input {@code input}参数
     * @param inputHash {@code inputHash}参数
     * @return 处理结果
     */
    private AgentDebugRunRow debugRow(
        Long ownerId,
        String idempotencyKey,
        Long agentId,
        Long versionId,
        Long taskId,
        Long runId,
        Long parentDebugRunId,
        String input,
        String inputHash
    ) {
        AgentDebugRunRow row = new AgentDebugRunRow();
        row.setId(idGenerator.nextId());
        row.setOwnerId(ownerId);
        row.setIdempotencyKey(idempotencyKey);
        row.setAgentId(agentId);
        row.setAgentVersionId(versionId);
        row.setTaskId(taskId);
        row.setRunId(runId);
        row.setParentDebugRunId(parentDebugRunId);
        row.setInputText(input);
        row.setInputSha256(inputHash);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    /**
     * 创建并保存{@code OrReplay}。
     *
     * @param row {@code row}参数
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @param inputHash {@code inputHash}参数
     */
    private void insertOrReplay(
        AgentDebugRunRow row,
        Long agentId,
        Long versionId,
        String inputHash
    ) {
        try {
            if (debugRunMapper.insert(row) != 1) {
                throw conflict("调试运行映射创建失败");
            }
        } catch (DuplicateKeyException exception) {
            AgentDebugRunRow existing = debugRunMapper.selectOwnedByIdempotencyKey(
                row.getOwnerId(), row.getIdempotencyKey()
            );
            if (existing == null) {
                throw exception;
            }
            requireSameCreate(existing, agentId, versionId, inputHash);
            row.setId(existing.getId());
            row.setTaskId(existing.getTaskId());
            row.setRunId(existing.getRunId());
        }
    }

    /**
     * 校验{@code SameCreate}，并在条件不满足时终止处理。
     *
     * @param existing {@code existing}参数
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @param inputHash {@code inputHash}参数
     */
    private void requireSameCreate(
        AgentDebugRunRow existing,
        Long agentId,
        Long versionId,
        String inputHash
    ) {
        if (!agentId.equals(existing.getAgentId())
            || !versionId.equals(existing.getAgentVersionId())
            || !inputHash.equals(existing.getInputSha256())) {
            throw conflict("同一调试幂等键不能用于不同Agent、版本或输入");
        }
    }

    /**
     * 处理{@code detail}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private AgentDebugRunDetailView detail(AgentDebugRunRow row) {
        AgentDebugRunSummaryView summary = summary(row);
        List<RunStepView> steps = runService.steps(row.getTaskId(), row.getRunId());
        EventBatch batch = allEvents(row);
        return new AgentDebugRunDetailView(
            summary, steps, metrics(summary.run(), batch), finalOutput(batch.events())
        );
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private AgentDebugRunSummaryView summary(AgentDebugRunRow row) {
        TaskRunView run = runService.get(row.getTaskId(), row.getRunId());
        AgentDefinition definition = definitionMapper.selectDefinitionById(row.getAgentId());
        AgentDefinitionVersion version = versionMapper.selectVersion(
            row.getAgentId(), row.getAgentVersionId()
        );
        if (definition == null || version == null) {
            throw conflict("调试运行引用的Agent版本已损坏");
        }
        return new AgentDebugRunSummaryView(
            row.getId(), row.getParentDebugRunId(), row.getAgentId(),
            definition.getAgentKey(), definition.getName(), row.getAgentVersionId(),
            version.getVersionNo(), version.getStatus(), row.getTaskId(), row.getInputText(),
            row.getInputSha256(), run, row.getCreatedAt()
        );
    }

    /**
     * 处理{@code allEvents}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private EventBatch allEvents(AgentDebugRunRow row) {
        List<ExecutionEventView> events = new ArrayList<>();
        long cursor = 0L;
        while (events.size() < MAX_DETAIL_EVENTS) {
            int limit = Math.min(EVENT_PAGE_SIZE, MAX_DETAIL_EVENTS - events.size());
            List<ExecutionEventView> page = eventViews(row, cursor, limit);
            if (page.isEmpty()) {
                break;
            }
            events.addAll(page);
            long next = page.getLast().cursor();
            if (next <= cursor || page.size() < limit) {
                break;
            }
            cursor = next;
        }
        return new EventBatch(List.copyOf(events), events.size() >= MAX_DETAIL_EVENTS);
    }

    /**
     * 处理事件Views并返回对应结果。
     *
     * @param row {@code row}参数
     * @param cursor {@code cursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    private List<ExecutionEventView> eventViews(AgentDebugRunRow row, long cursor, int limit) {
        return eventMapper.selectTaskRunEvents(row.getTaskId(), row.getRunId(), cursor, limit)
            .stream()
            .map(event -> ExecutionEventView.forExternal(event, jsonMapper))
            .toList();
    }

    /**
     * 处理{@code metrics}并返回对应结果。
     *
     * @param run {@code run}参数
     * @param batch {@code batch}参数
     * @return 处理结果
     */
    private AgentDebugMetricsView metrics(TaskRunView run, EventBatch batch) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        long promptTokens = 0L;
        long completionTokens = 0L;
        long cachedTokens = 0L;
        long totalTokens = 0L;
        long modelDurationMs = 0L;
        int modelCalls = 0;
        int toolCalls = 0;
        for (ExecutionEventView event : batch.events()) {
            if ("model_call_finished".equals(event.eventType())) {
                modelCalls++;
                promptTokens += number(event.projection().get("promptTokens"));
                completionTokens += number(event.projection().get("completionTokens"));
                cachedTokens += number(event.projection().get("cachedTokens"));
                totalTokens += number(event.projection().get("totalTokens"));
                modelDurationMs += number(event.projection().get("durationMs"));
            } else if ("tool_call_started".equals(event.eventType())) {
                toolCalls++;
            }
        }
        long elapsedMs = 0L;
        if (run.startedAt() != null) {
            LocalDateTime end = run.finishedAt() == null ? LocalDateTime.now() : run.finishedAt();
            elapsedMs = Math.max(0L, Duration.between(run.startedAt(), end).toMillis());
        }
        return new AgentDebugMetricsView(
            promptTokens, completionTokens, cachedTokens, totalTokens, elapsedMs,
            modelDurationMs, modelCalls, toolCalls, batch.events().size(), batch.truncated()
        );
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private long number(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    /**
     * 处理{@code finalOutput}并返回对应结果。
     *
     * @param events {@code events}参数
     * @return 处理结果
     */
    private String finalOutput(List<ExecutionEventView> events) {
        StringBuilder output = new StringBuilder();
        String result = "";
        for (ExecutionEventView event : events) {
            if ("text_delta".equals(event.eventType()) && output.length() < MAX_OUTPUT_CHARS) {
                String delta = event.summary() == null ? "" : event.summary();
                output.append(delta, 0, Math.min(delta.length(), MAX_OUTPUT_CHARS - output.length()));
            } else if ("result".equals(event.eventType()) && event.summary() != null) {
                result = event.summary();
            }
        }
        return output.isEmpty() ? result : output.toString();
    }

    /**
     * 校验{@code Owned}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param debugRunId 资源标识
     * @return 处理结果
     */
    private AgentDebugRunRow requireOwned(CurrentPrincipal principal, Long debugRunId) {
        AgentDebugRunRow row = debugRunMapper.selectOwned(debugRunId, principal.id());
        if (row == null) {
            throw notFound("调试运行不存在");
        }
        return row;
    }

    /**
     * 处理human操作主体并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal humanPrincipal() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            throw new ServiceException("Agent调试台仅支持登录用户", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理{@code normalizedInput}并返回对应结果。
     *
     * @param input {@code input}参数
     * @return 处理结果
     */
    private String normalizedInput(String input) {
        return input == null ? "" : input.strip();
    }

    /**
     * 处理{@code identifiers}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private String identifiers(AgentDebugRunRow row) {
        return "taskId=" + row.getTaskId() + ",runId=" + row.getRunId()
            + ",agentId=" + row.getAgentId() + ",versionId=" + row.getAgentVersionId()
            + ",inputSha256=" + row.getInputSha256();
    }

    /**
     * 处理{@code audited}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param debugRunId 资源标识
     * @param taskId 资源标识
     * @param summary {@code summary}参数
     * @param operation 操作参数
     * @return 处理结果
     */
    private <T> T audited(
        CurrentPrincipal principal,
        String action,
        Long debugRunId,
        Long taskId,
        String summary,
        Supplier<T> operation
    ) {
        try {
            T result = operation.get();
            auditService.record(
                principal, action, debugRunId, taskId, "success", "completed", summary
            );
            return result;
        } catch (ServiceException exception) {
            String decision = exception.getCode() == HttpStatus.FORBIDDEN
                || exception.getCode() == HttpStatus.NOT_FOUND ? "deny" : "failure";
            auditService.record(
                principal, action, debugRunId, taskId, decision,
                "http_" + exception.getCode() + ':' + exception.getMessage(), summary
            );
            throw exception;
        } catch (RuntimeException exception) {
            auditService.record(
                principal, action, debugRunId, taskId, "failure",
                "internal:" + exception.getClass().getSimpleName(), summary
            );
            throw exception;
        }
    }

    /**
     * 处理{@code auditedOwned}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param debugRunId 资源标识
     * @param operation 操作参数
     * @return 处理结果
     */
    private <T> T auditedOwned(
        CurrentPrincipal principal,
        String action,
        Long debugRunId,
        Function<AgentDebugRunRow, T> operation
    ) {
        AgentDebugRunRow row = null;
        try {
            row = requireOwned(principal, debugRunId);
            T result = operation.apply(row);
            auditService.record(
                principal, action, row.getId(), row.getTaskId(), "success", "completed",
                identifiers(row)
            );
            return result;
        } catch (ServiceException exception) {
            String decision = exception.getCode() == HttpStatus.FORBIDDEN
                || exception.getCode() == HttpStatus.NOT_FOUND ? "deny" : "failure";
            auditService.record(
                principal, action, debugRunId, row == null ? null : row.getTaskId(), decision,
                "http_" + exception.getCode() + ':' + exception.getMessage(),
                row == null ? "debugRunId=" + debugRunId : identifiers(row)
            );
            throw exception;
        } catch (RuntimeException exception) {
            auditService.record(
                principal, action, debugRunId, row == null ? null : row.getTaskId(), "failure",
                "internal:" + exception.getClass().getSimpleName(),
                row == null ? "debugRunId=" + debugRunId : identifiers(row)
            );
            throw exception;
        }
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /**
     * 处理{@code notFound}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.NOT_FOUND);
    }

    /**
     * 封装事件Batch相关的不可变数据。
     */
    private record EventBatch(List<ExecutionEventView> events, boolean truncated) {
    }
}
