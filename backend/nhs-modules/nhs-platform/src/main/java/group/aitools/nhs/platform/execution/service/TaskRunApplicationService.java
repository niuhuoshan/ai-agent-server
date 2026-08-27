package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.runtime.spi.RuntimeResumeMode;
import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.agent.service.AgentVersionContentHasher;
import group.aitools.nhs.platform.approval.domain.AgentApprovalRequest;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.domain.AgentRunStep;
import group.aitools.nhs.platform.execution.domain.AgentTaskRun;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.persistence.row.TaskRunDefinitionRow;
import group.aitools.nhs.platform.execution.persistence.row.ConversationTaskRunRow;
import group.aitools.nhs.platform.execution.web.CreateTaskRunRequest;
import group.aitools.nhs.platform.execution.web.RetryTaskRunRequest;
import group.aitools.nhs.platform.execution.web.TaskRunActionResult;
import group.aitools.nhs.platform.execution.web.TaskRunView;
import group.aitools.nhs.platform.execution.web.RunStepView;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.DecisionEvidence;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.task.service.TaskVersionContentHasher;
import group.aitools.nhs.platform.workflow.service.PreparedWorkflowRun;
import group.aitools.nhs.platform.workflow.service.WorkflowRunPreparationService;
import group.aitools.nhs.platform.workflow.service.WorkflowRunCoordinator;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * 负责任务Run相关的业务编排与领域规则处理。
 * Creates, claims and controls durable single-agent task runs. */
@Service
public class TaskRunApplicationService {

    private static final int MAX_INPUT_BYTES = 128 * 1024;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> CREATEABLE_TASK_STATUSES = Set.of(
        "ready", "scheduled", "rework", "blocked", "cancelled"
    );
    private static final Set<String> ACTIVE_RUN_STATUSES = Set.of(
        "queued", "preparing", "running", "waiting_approval", "waiting_input", "blocked", "paused"
    );
    private static final Set<String> RETRYABLE_RUN_STATUSES = Set.of(
        "failed", "cancelled", "expired"
    );
    private static final Set<String> MANUALLY_RESUMABLE_RUN_STATUSES = Set.of(
        "paused", "blocked", "waiting_input"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final TaskRunCommandMapper runMapper;
    private final TaskRunSnapshotFactory snapshotFactory;
    private final TaskRunExecutionCoordinator executionCoordinator;
    private final TaskVersionContentHasher taskHasher;
    private final AgentVersionContentHasher agentHasher;
    private final TaskQueryService taskQueryService;
    private final JsonMapper jsonMapper;
    private final WorkflowRunPreparationService workflowPreparationService;
    private final WorkflowRunCoordinator workflowRunCoordinator;

    /**
     * 创建 {@code TaskRunApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param runMapper {@code runMapper}参数
     * @param snapshotFactory 快照Factory参数
     * @param executionCoordinator 执行Coordinator参数
     * @param taskHasher 任务Hasher参数
     * @param agentHasher 智能体Hasher参数
     * @param taskQueryService 任务查询Service参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param workflowPreparationService 工作流PreparationService参数
     * @param workflowRunCoordinator 工作流RunCoordinator参数
     */
    @Autowired
    public TaskRunApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        TaskRunCommandMapper runMapper,
        TaskRunSnapshotFactory snapshotFactory,
        TaskRunExecutionCoordinator executionCoordinator,
        TaskVersionContentHasher taskHasher,
        AgentVersionContentHasher agentHasher,
        TaskQueryService taskQueryService,
        JsonMapper jsonMapper,
        WorkflowRunPreparationService workflowPreparationService,
        WorkflowRunCoordinator workflowRunCoordinator
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.runMapper = runMapper;
        this.snapshotFactory = snapshotFactory;
        this.executionCoordinator = executionCoordinator;
        this.taskHasher = taskHasher;
        this.agentHasher = agentHasher;
        this.taskQueryService = taskQueryService;
        this.jsonMapper = jsonMapper;
        this.workflowPreparationService = workflowPreparationService;
        this.workflowRunCoordinator = workflowRunCoordinator;
    }

    /**
     * 创建 {@code TaskRunApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param runMapper {@code runMapper}参数
     * @param snapshotFactory 快照Factory参数
     * @param executionCoordinator 执行Coordinator参数
     * @param taskHasher 任务Hasher参数
     * @param agentHasher 智能体Hasher参数
     * @param taskQueryService 任务查询Service参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public TaskRunApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        TaskRunCommandMapper runMapper,
        TaskRunSnapshotFactory snapshotFactory,
        TaskRunExecutionCoordinator executionCoordinator,
        TaskVersionContentHasher taskHasher,
        AgentVersionContentHasher agentHasher,
        TaskQueryService taskQueryService,
        JsonMapper jsonMapper
    ) {
        this(
            principalProvider, authorizationEnforcer, idGenerator, runMapper, snapshotFactory,
            executionCoordinator, taskHasher, agentHasher, taskQueryService, jsonMapper,
            null, null
        );
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param taskId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunActionResult create(Long taskId, CreateTaskRunRequest request) {
        return createAs(principalProvider.currentPrincipal(), taskId, null, request);
    }

    /**
 * 创建并保存{@code As}。
 * Creates a run as a pre-authenticated machine principal without consulting HTTP state. */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunActionResult createAs(
        CurrentPrincipal principal,
        Long taskId,
        Long expectedTaskVersionId,
        CreateTaskRunRequest request
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        String input = validateInput(request.input());
        String idempotencyKey = request.idempotencyKey().strip();
        runMapper.lockTask(taskId);
        TaskRunDefinitionRow definition = requireDefinition(taskId);
        requireExpectedTaskVersion(definition, expectedTaskVersionId);
        AuthorizationDecision taskDecision = authorizeTaskOperation(principal, definition, "operate");
        validateDefinitionIntegrity(definition);
        List<AgentVersionBindingRow> bindings = runMapper.selectBindings(definition.getAgentVersionId());
        validateAgentIntegrity(definition, bindings);
        validateTaskResourceSnapshot(definition, bindings);
        AuthorizationDecision agentDecision = authorizationEnforcer.requireAllowed(
            principal,
            context("agent_version", definition.getAgentVersionId(), definition.getAgentKey(), "use", taskId)
        );
        List<ResourceAuthorization> resourceAuthorizations = authorizeBindings(
            principal, taskId, bindings
        );

        String traceId = ContentHashing.sha256(
            "task-run\0" + taskId + "\0" + principal.type().name() + "\0"
                + principal.id() + "\0" + idempotencyKey
        );
        AgentTaskRun existing = runMapper.selectByTrace(taskId, traceId);
        if (existing != null) {
            requireSameIdempotentPayload(existing, definition, input);
            return new TaskRunActionResult(TaskRunView.from(existing), true);
        }
        requireCreateable(definition);

        Long runId = idGenerator.nextId();
        List<AgentRunStep> steps;
        String runAuthorizationJson;
        String runRuntimeJson;
        String runBudgetJson;
        if (definition.getWorkflowVersionId() != null) {
            PreparedWorkflowRun workflow = workflowPreparationService.prepare(
                definition, principal, taskDecision, runId, traceId, input
            );
            steps = workflow.steps();
            runAuthorizationJson = workflow.authorizationJson();
            runRuntimeJson = workflow.firstRuntimeJson();
            runBudgetJson = workflow.budgetJson();
        } else {
            Long stepId = idGenerator.nextId();
            Map<String, Object> authorizationSnapshot = authorizationSnapshot(
                principal, definition, taskDecision, agentDecision, resourceAuthorizations
            );
            TaskRunSnapshotFactory.FrozenRunSnapshot snapshot = snapshotFactory.create(
                definition, principal, runId, stepId, traceId, input,
                authorizationSnapshot, bindings
            );
            AgentRunStep step = singleAgentStep(
                definition, runId, stepId, input, snapshot, LocalDateTime.now()
            );
            steps = List.of(step);
            runAuthorizationJson = snapshot.authorizationJson();
            runRuntimeJson = snapshot.runtimeJson();
            runBudgetJson = snapshot.budgetJson();
        }
        LocalDateTime now = LocalDateTime.now();
        AgentTaskRun run = new AgentTaskRun();
        run.setId(runId);
        run.setTaskId(taskId);
        run.setTaskVersionId(definition.getTaskVersionId());
        run.setWorkflowVersionId(definition.getWorkflowVersionId());
        run.setTraceId(traceId);
        run.setStatus("queued");
        run.setAttemptNo(runMapper.selectNextAttempt(taskId));
        run.setParentRunId(definition.getLatestRunId());
        run.setAuthorizationSnapshotJson(runAuthorizationJson);
        run.setRuntimeSnapshotJson(runRuntimeJson);
        run.setBudgetSnapshotJson(runBudgetJson);
        run.setUsageJson("{}");
        run.setCreatedBy(principal.id());
        run.setCreatedAt(now);
        runMapper.insertRun(run);

        for (AgentRunStep step : steps) {
            if (runMapper.insertStep(step) != 1) {
                throw conflict("任务运行步骤创建失败");
            }
        }
        if (runMapper.bindLatestRun(
            taskId, definition.getTaskVersionId(), runId, principal.id()
        ) != 1) {
            throw conflict("任务版本在创建运行时发生变化");
        }
        return new TaskRunActionResult(TaskRunView.from(run), false);
    }

    /**
     * 处理single智能体Step并返回对应结果。
     *
     * @param definition 定义参数
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param input {@code input}参数
     * @param snapshot 快照参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentRunStep singleAgentStep(
        TaskRunDefinitionRow definition,
        Long runId,
        Long stepId,
        String input,
        TaskRunSnapshotFactory.FrozenRunSnapshot snapshot,
        LocalDateTime now
    ) {
        AgentRunStep step = new AgentRunStep();
        step.setId(stepId);
        step.setRunId(runId);
        step.setStepKey("agent-" + definition.getAgentVersionId());
        step.setStepType("agent");
        step.setDependsOnJson("[]");
        step.setSequenceNo(1);
        step.setStatus("pending");
        step.setAgentVersionId(definition.getAgentVersionId());
        step.setInputSummary(input.length() <= 512 ? input : input.substring(0, 512));
        step.setInputJson(jsonMapper.writeValueAsString(Map.of("text", input)));
        step.setRuntimeTemplateJson(snapshot.runtimeJson());
        step.setRuntimeSnapshotJson(snapshot.runtimeJson());
        step.setAuthorizationSnapshotJson(snapshot.authorizationJson());
        step.setCreatedAt(now);
        return step;
    }

    /**
 * 校验{@code As}，并在条件不满足时终止处理。
 * Performs the same live authorization and integrity checks without creating a run. */
    public void validateAs(
        CurrentPrincipal principal,
        Long taskId,
        Long expectedTaskVersionId
    ) {
        TaskRunDefinitionRow definition = requireDefinition(taskId);
        requireExpectedTaskVersion(definition, expectedTaskVersionId);
        authorizeTaskOperation(principal, definition, "operate");
        validateDefinitionIntegrity(definition);
        List<AgentVersionBindingRow> bindings = runMapper.selectBindings(definition.getAgentVersionId());
        validateAgentIntegrity(definition, bindings);
        validateTaskResourceSnapshot(definition, bindings);
        authorizationEnforcer.requireAllowed(
            principal,
            context("agent_version", definition.getAgentVersionId(), definition.getAgentKey(), "use", taskId)
        );
        authorizeBindings(principal, taskId, bindings);
    }

    /**
     * 查询{@code list}列表。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<TaskRunView> list(Long taskId, int limit) {
        taskQueryService.get(taskId);
        return runMapper.selectRuns(taskId, limit).stream().map(TaskRunView::from).toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    public TaskRunView get(Long taskId, Long runId) {
        taskQueryService.get(taskId);
        return TaskRunView.from(requireRun(taskId, runId));
    }

    /**
     * 处理{@code steps}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 符合条件的数据集合
     */
    public List<RunStepView> steps(Long taskId, Long runId) {
        taskQueryService.get(taskId);
        requireRun(taskId, runId);
        return runMapper.selectSteps(runId).stream()
            .map(step -> RunStepView.from(step, jsonMapper)).toList();
    }

    /**
     * 获取{@code As}。
     *
     * @param principal 当前操作主体
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    public TaskRunView getAs(CurrentPrincipal principal, Long taskId, Long runId) {
        TaskRunDefinitionRow definition = requireDefinition(taskId);
        authorizeTaskOperation(principal, definition, "view");
        return TaskRunView.from(requireRun(taskId, runId));
    }

    /**
     * 处理{@code start}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunActionResult start(Long taskId, Long runId) {
        return startAs(principalProvider.currentPrincipal(), taskId, runId, null);
    }

    /**
 * 处理{@code startAs}并返回对应结果。
 * Starts a frozen run only when it still belongs to the explicit machine principal. */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunActionResult startAs(
        CurrentPrincipal principal,
        Long taskId,
        Long runId,
        Long expectedTaskVersionId
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        runMapper.lockTask(taskId);
        TaskRunDefinitionRow definition = requireDefinition(taskId);
        requireExpectedTaskVersion(definition, expectedTaskVersionId);
        authorizeTaskOperation(principal, definition, "operate");
        AgentTaskRun run = requireRun(taskId, runId);
        AgentRunRequest runtimeRequest = parseAndValidateRuntimeSnapshot(run);
        requireRunCreatorOrAdmin(principal, run, runtimeRequest);
        if (definition.getStartAt() != null && definition.getStartAt().isAfter(LocalDateTime.now())) {
            throw conflict("任务尚未到开始时间");
        }
        if (!executionCoordinator.available()) {
            throw new ServiceException("AgentScope运行时未启用", 503);
        }
        if ("running".equals(run.getStatus()) || "preparing".equals(run.getStatus())) {
            boolean leaseExpired = run.getLeaseUntil() != null
                && run.getLeaseUntil().isBefore(LocalDateTime.now());
            if (!leaseExpired || executionCoordinator.isLocallyActive(runId)) {
                return new TaskRunActionResult(TaskRunView.from(run), true);
            }
        }
        if (!"queued".equals(run.getStatus())
            && !"running".equals(run.getStatus())
            && !"preparing".equals(run.getStatus())) {
            throw conflict("当前运行状态不能启动：" + run.getStatus());
        }
        if (runMapper.claimRun(taskId, runId, executionCoordinator.workerId()) != 1) {
            AgentTaskRun raced = requireRun(taskId, runId);
            return new TaskRunActionResult(TaskRunView.from(raced), true);
        }
        runMapper.markTaskRunning(taskId, runId, principal.id());
        if (run.getWorkflowVersionId() != null) {
            workflowRunCoordinator.startReadyAfterCommit(
                runId, taskId, executionCoordinator.workerId()
            );
        } else {
            if (runMapper.startStep(runId, runtimeRequest.stepId()) != 1) {
                throw conflict("运行步骤不能启动");
            }
            launchAfterCommit(runtimeRequest);
        }
        return new TaskRunActionResult(TaskRunView.from(requireRun(taskId, runId)), false);
    }

    /**
     * 处理{@code pause}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunActionResult pause(Long taskId, Long runId, String reason) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String normalizedReason = normalizeActionReason(reason, "用户暂停任务运行");
        runMapper.lockTask(taskId);
        TaskRunDefinitionRow definition = requireDefinition(taskId);
        authorizeTaskOperation(principal, definition, "operate");
        AgentTaskRun run = requireRun(taskId, runId);
        requireRunCreatorOrAdmin(principal, run);
        if ("paused".equals(run.getStatus())) {
            return new TaskRunActionResult(TaskRunView.from(run), true);
        }
        if (!Set.of("preparing", "running").contains(run.getStatus())) {
            throw conflict("当前运行状态不能暂停：" + run.getStatus());
        }
        AgentRunRequest runtimeRequest = parseAndValidateRuntimeSnapshot(run);
        if (runMapper.pauseRun(taskId, runId, normalizedReason) != 1) {
            return new TaskRunActionResult(TaskRunView.from(requireRun(taskId, runId)), true);
        }
        if (run.getWorkflowVersionId() != null) {
            workflowRunCoordinator.pauseStepsAfterCommit(runId, normalizedReason);
        } else {
            runMapper.markStepWaiting(runId, runtimeRequest.stepId());
            afterCommit(() -> executionCoordinator.requestCancellation(runtimeRequest, normalizedReason));
        }
        runMapper.markTaskBlocked(taskId, runId);
        return new TaskRunActionResult(TaskRunView.from(requireRun(taskId, runId)), false);
    }

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunActionResult resume(Long taskId, Long runId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        runMapper.lockTask(taskId);
        TaskRunDefinitionRow definition = requireDefinition(taskId);
        authorizeTaskOperation(principal, definition, "operate");
        AgentTaskRun run = requireRun(taskId, runId);
        requireRunCreatorOrAdmin(principal, run);
        if ("running".equals(run.getStatus()) || "preparing".equals(run.getStatus())) {
            return new TaskRunActionResult(TaskRunView.from(run), true);
        }
        if (!MANUALLY_RESUMABLE_RUN_STATUSES.contains(run.getStatus())) {
            throw conflict("当前运行状态不能恢复：" + run.getStatus());
        }
        if (!executionCoordinator.available()) {
            throw new ServiceException("AgentScope运行时未启用", 503);
        }
        AgentRunRequest frozen = parseAndValidateRuntimeSnapshot(run);
        if (run.getWorkflowVersionId() != null && !"paused".equals(run.getStatus())) {
            throw conflict("多智能体工作流只能人工恢复用户主动暂停的步骤");
        }
        if (runMapper.claimResumedRun(taskId, runId, executionCoordinator.workerId()) != 1) {
            return new TaskRunActionResult(TaskRunView.from(requireRun(taskId, runId)), true);
        }
        runMapper.markTaskRunning(taskId, runId, principal.id());
        if (run.getWorkflowVersionId() != null) {
            if (!workflowRunCoordinator.resumePausedAfterCommit(runId, principal.id())) {
                throw conflict("多智能体工作流没有可人工恢复的暂停步骤");
            }
            return new TaskRunActionResult(TaskRunView.from(requireRun(taskId, runId)), false);
        }
        if (runMapper.startStep(runId, frozen.stepId()) != 1) {
            throw conflict("运行步骤不能恢复");
        }
        AgentResumeRequest resumeRequest = new AgentResumeRequest(
            frozen.executionKey(),
            frozen.userId(),
            frozen.conversationId(),
            frozen.taskId(),
            frozen.runId(),
            frozen.stepId(),
            frozen.sessionId(),
            "manual-resume-" + runId,
            RuntimeResumeDecision.APPROVE,
            Map.of(),
            Map.of("actorId", principal.id(), "reason", "manual_resume"),
            RuntimeResumeMode.CONTINUE
        ).withRuntimeContext(frozen);
        launchResumeAfterCommit(resumeRequest);
        return new TaskRunActionResult(TaskRunView.from(requireRun(taskId, runId)), false);
    }

    /**
     * 处理{@code retry}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunActionResult retry(Long taskId, Long runId, RetryTaskRunRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        runMapper.lockTask(taskId);
        TaskRunDefinitionRow definition = requireDefinition(taskId);
        authorizeTaskOperation(principal, definition, "retry");
        AgentTaskRun parent = requireRun(taskId, runId);
        if (!RETRYABLE_RUN_STATUSES.contains(parent.getStatus())) {
            throw conflict("当前运行状态不能重试：" + parent.getStatus());
        }
        if (!runId.equals(definition.getLatestRunId())) {
            throw conflict("只能重试任务的最新运行");
        }
        AgentRunRequest frozen = parseAndValidateRuntimeSnapshot(parent);
        String retryKey = "retry:" + runId + ":" + request.idempotencyKey().strip();
        TaskRunActionResult created = create(
            taskId,
            new CreateTaskRunRequest(retryKey, originalInput(frozen))
        );
        if (!request.startImmediately()) {
            return created;
        }
        return start(taskId, created.run().id());
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunActionResult cancel(Long taskId, Long runId, String reason) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String normalizedReason = normalizeActionReason(reason, "用户取消任务运行");
        runMapper.lockTask(taskId);
        TaskRunDefinitionRow definition = requireDefinition(taskId);
        authorizeTaskOperation(principal, definition, "cancel");
        AgentTaskRun run = requireRun(taskId, runId);
        if (!ACTIVE_RUN_STATUSES.contains(run.getStatus())) {
            return new TaskRunActionResult(TaskRunView.from(run), true);
        }
        AgentRunRequest runtimeRequest = parseAndValidateRuntimeSnapshot(run);
        if (run.getWorkflowVersionId() != null) {
            workflowRunCoordinator.cancelActiveAfterCommit(runId, normalizedReason);
        } else {
            executionCoordinator.requestCancellation(runtimeRequest, normalizedReason);
        }
        if (runMapper.cancelRun(taskId, runId, normalizedReason) != 1) {
            return new TaskRunActionResult(TaskRunView.from(requireRun(taskId, runId)), true);
        }
        runMapper.cancelSteps(runId);
        runMapper.markTaskCancelled(taskId, runId, principal.id());
        return new TaskRunActionResult(TaskRunView.from(requireRun(taskId, runId)), false);
    }

    /**
 * 判断celActiveFor会话是否满足要求。
 *
     * Cancels runs created from a private conversation.  The task itself may
     * be enterprise-shared, so the mapper deliberately filters by run creator
     * before this method reuses the normal task authorization and cancellation
     * path for every run.
     */
    @Transactional(rollbackFor = Exception.class)
    public List<TaskRunActionResult> cancelActiveForConversation(
        Long conversationId,
        String reason
    ) {
        if (conversationId == null || conversationId <= 0) {
            throw new ServiceException("会话ID无效", HttpStatus.BAD_REQUEST);
        }
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String normalizedReason = normalizeActionReason(reason, "用户取消会话关联任务");
        List<ConversationTaskRunRow> rows = runMapper.selectActiveRunsForConversation(
            conversationId, principal.id()
        );
        List<TaskRunActionResult> results = new ArrayList<>(rows.size());
        for (ConversationTaskRunRow row : rows) {
            if (row == null || row.getTaskId() == null || row.getRunId() == null) {
                continue;
            }
            results.add(cancel(row.getTaskId(), row.getRunId(), normalizedReason));
        }
        return List.copyOf(results);
    }

    /**
 * 处理resumeFrom审批并返回对应结果。
 * Resumes exactly the persisted AgentScope confirmation after an atomic approval decision. */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunView resumeFromApproval(
        AgentApprovalRequest approval,
        CurrentPrincipal reviewer,
        List<Map<String, Object>> pendingActions
    ) {
        return resumeFromApproval(
            approval, reviewer, pendingActions, RuntimeResumeDecision.APPROVE, "approved"
        );
    }

    /**
 * 处理resumeFrom审批并返回对应结果。
 * Resumes the same persisted approval for either a business confirmation decision. */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunView resumeFromApproval(
        AgentApprovalRequest approval,
        CurrentPrincipal reviewer,
        List<Map<String, Object>> pendingActions,
        RuntimeResumeDecision decision,
        String source
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (decision == null || source == null || source.isBlank()) {
            throw new ServiceException("恢复决策无效", HttpStatus.BAD_REQUEST);
        }
        AgentTaskRun run = requireApprovalRun(approval);
        boolean workflow = run.getWorkflowVersionId() != null;
        if (workflow
            ? !workflowRunCoordinator.isWaitingStep(
                approval.getRunId(), approval.getStepId(), "tool_approval"
            )
            : !"waiting_approval".equals(run.getStatus())) {
            throw conflict("审批关联的运行不再等待审批：" + run.getStatus());
        }
        if (!executionCoordinator.available()) {
            throw new ServiceException("AgentScope运行时未启用", 503);
        }
        AgentRunRequest frozen = parseAndValidateRuntimeSnapshot(run, approval.getStepId());
        requireApprovalIdentity(approval, frozen);
        if (workflow) {
            if (!workflowRunCoordinator.resumeWaitingStep(
                approval.getRunId(), approval.getStepId()
            )) {
                throw conflict("审批关联的工作流步骤发生变化");
            }
        } else {
            if (runMapper.claimApprovedRun(
                approval.getTaskId(), approval.getRunId(), executionCoordinator.workerId()
            ) != 1) {
                throw conflict("审批关联的运行状态发生变化");
            }
            if (runMapper.startStep(approval.getRunId(), approval.getStepId()) != 1) {
                throw conflict("审批关联的运行步骤不能恢复");
            }
        }
        runMapper.markTaskRunning(approval.getTaskId(), approval.getRunId(), reviewer.id());
        AgentResumeRequest resumeRequest = new AgentResumeRequest(
            frozen.executionKey(),
            frozen.userId(),
            frozen.conversationId(),
            frozen.taskId(),
            frozen.runId(),
            frozen.stepId(),
            frozen.sessionId(),
            approval.getReplyId(),
            decision,
            pendingActions,
            Map.of(
                "approvalId", approval.getId(),
                "reviewerId", reviewer.id(),
                "decision", source
            ),
            RuntimeResumeMode.APPROVAL
        ).withRuntimeContext(frozen);
        launchResumeAfterCommit(resumeRequest);
        return TaskRunView.from(requireRun(approval.getTaskId(), approval.getRunId()));
    }

    /**
 * 处理rejectFrom审批并返回对应结果。
 * Rejects a pending side effect without resuming AgentScope or executing any tool. */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunView rejectFromApproval(AgentApprovalRequest approval) {
        AgentTaskRun run = requireApprovalRun(approval);
        if (run.getWorkflowVersionId() != null
            ? !workflowRunCoordinator.isWaitingStep(
                approval.getRunId(), approval.getStepId(), "tool_approval"
            )
            : !"waiting_approval".equals(run.getStatus())) {
            throw conflict("审批关联的运行不再等待审批：" + run.getStatus());
        }
        AgentRunRequest frozen = parseAndValidateRuntimeSnapshot(run, approval.getStepId());
        requireApprovalIdentity(approval, frozen);
        if (runMapper.failRun(
            approval.getRunId(), "APPROVAL_REJECTED", "高风险工具调用审批被拒绝"
        ) != 1) {
            throw conflict("审批关联的运行状态发生变化");
        }
        if (runMapper.failStep(
            approval.getRunId(), approval.getStepId(),
            "APPROVAL_REJECTED", "高风险工具调用审批被拒绝"
        ) != 1) {
            throw conflict("审批关联的运行步骤状态发生变化");
        }
        return TaskRunView.from(requireRun(approval.getTaskId(), approval.getRunId()));
    }

    /**
 * 处理expireFrom审批并返回对应结果。
 * Expires a waiting approval and releases its durable run from an indefinite wait. */
    @Transactional(rollbackFor = Exception.class)
    public TaskRunView expireFromApproval(AgentApprovalRequest approval) {
        AgentTaskRun run = requireApprovalRun(approval);
        if (run.getWorkflowVersionId() != null
            ? !workflowRunCoordinator.isWaitingStep(
                approval.getRunId(), approval.getStepId(), "tool_approval"
            )
            : !"waiting_approval".equals(run.getStatus())) {
            return TaskRunView.from(run);
        }
        AgentRunRequest frozen = parseAndValidateRuntimeSnapshot(run, approval.getStepId());
        requireApprovalIdentity(approval, frozen);
        int expired = run.getWorkflowVersionId() != null
            ? runMapper.failRun(
                approval.getRunId(), "APPROVAL_EXPIRED", "工具审批已过期"
            )
            : runMapper.expireApprovalRun(approval.getTaskId(), approval.getRunId());
        if (expired != 1) {
            throw conflict("审批关联的运行状态发生变化");
        }
        if (runMapper.failStep(
            approval.getRunId(), approval.getStepId(), "APPROVAL_EXPIRED", "工具审批已过期"
        ) != 1) {
            throw conflict("审批关联的运行步骤状态发生变化");
        }
        return TaskRunView.from(requireRun(approval.getTaskId(), approval.getRunId()));
    }

    /**
     * 校验定义，并在条件不满足时终止处理。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    private TaskRunDefinitionRow requireDefinition(Long taskId) {
        TaskRunDefinitionRow definition = runMapper.selectDefinition(taskId);
        if (definition == null) {
            throw new ServiceException("任务不存在或执行配置不完整", HttpStatus.NOT_FOUND);
        }
        return definition;
    }

    /**
     * 校验{@code Run}，并在条件不满足时终止处理。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    private AgentTaskRun requireRun(Long taskId, Long runId) {
        AgentTaskRun run = runMapper.selectRun(taskId, runId);
        if (run == null) {
            throw new ServiceException("任务运行不存在", HttpStatus.NOT_FOUND);
        }
        return run;
    }

    /**
     * 校验审批Run，并在条件不满足时终止处理。
     *
     * @param approval 审批参数
     * @return 处理结果
     */
    private AgentTaskRun requireApprovalRun(AgentApprovalRequest approval) {
        if (approval == null || approval.getTaskId() == null || approval.getRunId() == null
            || approval.getStepId() == null) {
            throw conflict("审批没有完整的任务运行身份");
        }
        return requireRun(approval.getTaskId(), approval.getRunId());
    }

    /**
     * 校验审批身份，并在条件不满足时终止处理。
     *
     * @param approval 审批参数
     * @param frozen {@code frozen}参数
     */
    private void requireApprovalIdentity(AgentApprovalRequest approval, AgentRunRequest frozen) {
        if (!approval.getTaskId().equals(frozen.taskId())
            || !approval.getRunId().equals(frozen.runId())
            || !approval.getStepId().equals(frozen.stepId())
            || approval.getReplyId() == null || approval.getReplyId().isBlank()) {
            throw conflict("审批身份与冻结运行快照不一致");
        }
    }

    /**
     * 处理authorize任务操作并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param definition 定义参数
     * @param action {@code action}参数
     * @return 处理结果
     */
    private AuthorizationDecision authorizeTaskOperation(
        CurrentPrincipal principal,
        TaskRunDefinitionRow definition,
        String action
    ) {
        Set<BusinessRelation> relations = runMapper.selectRelations(
            definition.getTaskId(), principal.id(), principal.type().name().toLowerCase(Locale.ROOT)
        ).stream().map(BusinessRelation::valueOf)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "task",
            definition.getTaskId(),
            null,
            action,
            ResourceState.ACTIVE,
            principal.isHuman(),
            relations,
            definition.getTaskId()
        ));
    }

    /**
     * 处理{@code authorizeBindings}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param taskId 资源标识
     * @param bindings {@code bindings}参数
     * @return 符合条件的数据集合
     */
    private List<ResourceAuthorization> authorizeBindings(
        CurrentPrincipal principal,
        Long taskId,
        List<AgentVersionBindingRow> bindings
    ) {
        List<ResourceAuthorization> result = new ArrayList<>(bindings.size());
        for (AgentVersionBindingRow binding : bindings) {
            String action = switch (binding.getResourceType()) {
                case "tool" -> "invoke";
                case "skill" -> "use";
                case "knowledge_base" -> "read";
                default -> throw conflict("Agent版本包含未知资源类型");
            };
            AuthorizationDecision decision = authorizationEnforcer.decide(
                principal,
                context(binding.getResourceType(), binding.getResourceId(), null, action, taskId)
            );
            if (!decision.allowed() && !decision.requiresApproval()) {
                throw new ServiceException(
                    "任务资源没有执行权限：" + binding.getResourceType() + ":"
                        + binding.getResourceId() + "（" + decision.reasonCode() + "）",
                    HttpStatus.FORBIDDEN
                );
            }
            result.add(new ResourceAuthorization(binding, action, decision));
        }
        return List.copyOf(result);
    }

    /**
     * 校验定义Integrity，并在条件不满足时终止处理。
     *
     * @param definition 定义参数
     */
    private void validateDefinitionIntegrity(TaskRunDefinitionRow definition) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        boolean multiAgent = "multi_agent_template".equals(definition.getOrchestrationMode());
        if (multiAgent != (definition.getWorkflowVersionId() != null)) {
            throw conflict("任务编排模式与工作流版本绑定不一致");
        }
        String hash = taskHasher.hash(
            definition.getTaskVersionTitle(),
            definition.getTaskVersionObjective(),
            definition.getTaskContextSnapshotJson(),
            definition.getTaskResourceSnapshotJson(),
            definition.getTaskAcceptanceSnapshotJson(),
            definition.getTaskInputSnapshotJson()
        );
        if (!hash.equals(definition.getTaskContentHash())) {
            throw conflict("任务版本内容哈希不一致，拒绝执行");
        }
        if (!"active".equals(definition.getAgentStatus())) {
            throw conflict("任务绑定的Agent当前不可用");
        }
        if (definition.getAgentPublishedAt() == null
            || !Set.of("published", "archived").contains(definition.getAgentVersionStatus())) {
            throw conflict("任务必须绑定曾经发布且不可变的Agent版本");
        }
    }

    /**
     * 校验智能体Integrity，并在条件不满足时终止处理。
     *
     * @param definition 定义参数
     * @param bindings {@code bindings}参数
     */
    private void validateAgentIntegrity(
        TaskRunDefinitionRow definition,
        List<AgentVersionBindingRow> bindings
    ) {
        AgentDefinitionVersion version = new AgentDefinitionVersion();
        version.setId(definition.getAgentVersionId());
        version.setAgentId(definition.getAgentId());
        version.setSystemPrompt(definition.getSystemPrompt());
        version.setModelId(definition.getModelId());
        version.setSynthesisModelId(definition.getSynthesisModelId());
        version.setRuntimeConfigJson(definition.getAgentRuntimeConfigJson());
        version.setWelcomeConfigJson(definition.getAgentWelcomeConfigJson());
        version.setRoutingTagsJson(definition.getAgentRoutingTagsJson());
        if (!agentHasher.hash(version, bindings).equals(definition.getAgentContentHash())) {
            throw conflict("Agent版本内容哈希不一致，拒绝执行");
        }
    }

    /**
     * 校验任务资源快照，并在条件不满足时终止处理。
     *
     * @param definition 定义参数
     * @param bindings {@code bindings}参数
     */
    private void validateTaskResourceSnapshot(
        TaskRunDefinitionRow definition,
        List<AgentVersionBindingRow> bindings
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Map<String, Object> snapshot;
        try {
            snapshot = jsonMapper.readValue(definition.getTaskResourceSnapshotJson(), MAP_TYPE);
        } catch (RuntimeException exception) {
            throw conflict("任务资源快照无效");
        }
        Object agentVersionId = snapshot.get("agentVersionId");
        if (!(agentVersionId instanceof Number number)
            || number.longValue() != definition.getAgentVersionId()) {
            throw conflict("任务资源快照的Agent版本不一致");
        }
        Object rawResources = snapshot.get("resources");
        if (!(rawResources instanceof List<?> resources)) {
            if (bindings.isEmpty()) {
                return;
            }
            throw conflict("任务资源快照没有冻结Agent能力授权");
        }

        Set<TaskResourceGrant> grants = new LinkedHashSet<>();
        for (Object value : resources) {
            if (!(value instanceof Map<?, ?> resource)
                || !(resource.get("resourceType") instanceof String resourceType)
                || !(resource.get("resourceId") instanceof Number resourceId)
                || !(resource.get("permission") instanceof String permission)) {
                throw conflict("任务资源快照包含无效资源");
            }
            grants.add(new TaskResourceGrant(resourceType, resourceId.longValue(), permission));
        }
        for (AgentVersionBindingRow binding : bindings) {
            String requiredPermission = switch (binding.getResourceType()) {
                case "tool", "skill" -> "use";
                case "knowledge_base" -> "read";
                default -> throw conflict("Agent版本包含未知资源类型");
            };
            boolean granted = grants.contains(new TaskResourceGrant(
                binding.getResourceType(), binding.getResourceId(), requiredPermission
            )) || grants.contains(new TaskResourceGrant(
                binding.getResourceType(), binding.getResourceId(), "admin"
            ));
            if (!granted) {
                throw new ServiceException(
                    "任务资源快照未授权Agent能力：" + binding.getResourceType() + ":"
                        + binding.getResourceId(),
                    HttpStatus.FORBIDDEN
                );
            }
        }
    }

    /**
     * 校验{@code Createable}，并在条件不满足时终止处理。
     *
     * @param definition 定义参数
     */
    private void requireCreateable(TaskRunDefinitionRow definition) {
        if (!CREATEABLE_TASK_STATUSES.contains(definition.getTaskStatus())) {
            throw conflict("当前任务状态不能创建运行：" + definition.getTaskStatus());
        }
        if (definition.getLatestRunId() != null && ACTIVE_RUN_STATUSES.contains(
            definition.getLatestRunStatus()
        )) {
            throw conflict("任务已有未结束的运行");
        }
    }

    /**
     * 校验{@code SameIdempotentPayload}，并在条件不满足时终止处理。
     *
     * @param existing {@code existing}参数
     * @param definition 定义参数
     * @param input {@code input}参数
     */
    private void requireSameIdempotentPayload(
        AgentTaskRun existing,
        TaskRunDefinitionRow definition,
        String input
    ) {
        AgentRunRequest frozen = parseAndValidateRuntimeSnapshot(existing);
        if (!definition.getTaskVersionId().equals(existing.getTaskVersionId())
            || !input.equals(originalInput(frozen))) {
            throw conflict("同一幂等键不能用于不同的任务版本或输入");
        }
    }

    /**
     * 处理parseAndValidate运行时快照并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    private AgentRunRequest parseAndValidateRuntimeSnapshot(AgentTaskRun run) {
        return parseAndValidateRuntimeSnapshot(run, null);
    }

    /**
     * 处理parseAndValidate运行时快照并返回对应结果。
     *
     * @param run {@code run}参数
     * @param stepId 资源标识
     * @return 处理结果
     */
    private AgentRunRequest parseAndValidateRuntimeSnapshot(AgentTaskRun run, Long stepId) {
        try {
            String runtimeJson = stepId != null && run.getWorkflowVersionId() != null
                ? runMapper.selectStepRuntimeSnapshot(run.getId(), stepId)
                : run.getRuntimeSnapshotJson();
            AgentRunRequest request = jsonMapper.readValue(runtimeJson, AgentRunRequest.class);
            if (request == null
                || !run.getId().equals(request.runId())
                || !run.getTaskId().equals(request.taskId())
                || !samePositiveId(run.getTaskVersionId(), request.attributes().get("taskVersionId"))
                || (stepId != null && !stepId.equals(request.stepId()))
                || !run.getTraceId().equals(request.executionKey().traceId())) {
                throw conflict("运行快照身份校验失败");
            }
            return request;
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("持久化运行快照无效");
        }
    }

    /**
     * 处理{@code samePositiveId}并返回对应结果。
     *
     * @param expected {@code expected}参数
     * @param actual {@code actual}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean samePositiveId(Long expected, Object actual) {
        if (!(actual instanceof Number number)) {
            return false;
        }
        long value = number.longValue();
        return value > 0 && number.doubleValue() == value && expected.longValue() == value;
    }

    /**
     * 处理{@code originalInput}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private String originalInput(AgentRunRequest request) {
        Object workflowInput = request.attributes().get("workflowInput");
        return workflowInput instanceof String text && !text.isBlank()
            ? text : request.input();
    }

    /**
     * 处理授权快照并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param definition 定义参数
     * @param taskDecision 任务Decision参数
     * @param agentDecision 智能体Decision参数
     * @param resourceAuthorizations 资源Authorizations参数
     * @return 处理结果
     */
    private Map<String, Object> authorizationSnapshot(
        CurrentPrincipal principal,
        TaskRunDefinitionRow definition,
        AuthorizationDecision taskDecision,
        AuthorizationDecision agentDecision,
        List<ResourceAuthorization> resourceAuthorizations
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("principalId", principal.id());
        result.put("principalType", principal.type().name().toLowerCase(java.util.Locale.ROOT));
        result.put("roles", principal.roles().stream().map(PlatformRole::key).sorted().toList());
        result.put("taskId", definition.getTaskId());
        result.put("taskVersionId", definition.getTaskVersionId());
        result.put("agentVersionId", definition.getAgentVersionId());
        result.put("taskDecision", decisionMap(taskDecision));
        result.put("agentDecision", decisionMap(agentDecision));
        result.put("resourceDecisions", resourceAuthorizations.stream().map(item -> Map.of(
            "resourceType", item.binding().getResourceType(),
            "resourceId", item.binding().getResourceId(),
            "action", item.action(),
            "decision", decisionMap(item.decision())
        )).toList());
        Map<String, Object> runtime = jsonMapper.readValue(
            definition.getAgentRuntimeConfigJson(), MAP_TYPE
        );
        result.put("workspaceAccess", runtime.getOrDefault("workspaceAccess", "none"));
        result.put("frozenAt", LocalDateTime.now().toString());
        return Map.copyOf(result);
    }

    /**
     * 处理{@code decisionMap}并返回对应结果。
     *
     * @param decision {@code decision}参数
     * @return 处理结果
     */
    private Map<String, Object> decisionMap(AuthorizationDecision decision) {
        return Map.of(
            "effect", decision.effect().name().toLowerCase(java.util.Locale.ROOT),
            "reasonCode", decision.reasonCode(),
            "evidence", decision.evidence().stream().map(this::evidenceMap).toList()
        );
    }

    /**
     * 处理{@code evidenceMap}并返回对应结果。
     *
     * @param evidence {@code evidence}参数
     * @return 处理结果
     */
    private Map<String, Object> evidenceMap(DecisionEvidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", evidence.source().name().toLowerCase(java.util.Locale.ROOT));
        value.put("sourceReference", evidence.sourceReference());
        value.put("effect", evidence.effect().name().toLowerCase(java.util.Locale.ROOT));
        value.put("reason", evidence.reason());
        return value;
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param resourceKey 资源Key参数
     * @param action {@code action}参数
     * @param taskId 资源标识
     * @return 处理结果
     */
    private PermissionContext context(
        String resourceType,
        Long resourceId,
        String resourceKey,
        String action,
        Long taskId
    ) {
        return new PermissionContext(
            resourceType, resourceId, resourceKey, action,
            ResourceState.ACTIVE, false, Set.of(), taskId
        );
    }

    /**
     * 校验{@code RunCreatorOrAdmin}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param run {@code run}参数
     * @param frozen {@code frozen}参数
     */
    private void requireRunCreatorOrAdmin(
        CurrentPrincipal principal,
        AgentTaskRun run,
        AgentRunRequest frozen
    ) {
        Map<String, Object> authorization = frozen.authorizationSnapshot();
        boolean samePrincipal = principal.id().equals(run.getCreatedBy())
            && samePositiveId(principal.id(), authorization.get("principalId"))
            && principal.type().name().equalsIgnoreCase(String.valueOf(
                authorization.get("principalType")
            ));
        if (!samePrincipal
            && !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("只有运行创建者或平台管理员可以启动该运行", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 校验{@code RunCreatorOrAdmin}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param run {@code run}参数
     */
    private void requireRunCreatorOrAdmin(CurrentPrincipal principal, AgentTaskRun run) {
        requireRunCreatorOrAdmin(principal, run, parseAndValidateRuntimeSnapshot(run));
    }

    /**
     * 校验Expected任务版本，并在条件不满足时终止处理。
     *
     * @param definition 定义参数
     * @param expectedTaskVersionId 资源标识
     */
    private void requireExpectedTaskVersion(
        TaskRunDefinitionRow definition,
        Long expectedTaskVersionId
    ) {
        if (expectedTaskVersionId != null
            && !expectedTaskVersionId.equals(definition.getTaskVersionId())) {
            throw conflict("自动化触发器绑定的任务版本已发生变化");
        }
    }

    /**
     * 校验{@code Input}，并在条件不满足时终止处理。
     *
     * @param input {@code input}参数
     * @return 处理结果
     */
    private String validateInput(String input) {
        String normalized = input == null ? "" : input.strip();
        if (normalized.isBlank() || normalized.indexOf('\0') >= 0
            || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new ServiceException("任务运行输入为空或超过128KB限制", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code normalizeActionReason}并返回对应结果。
     *
     * @param reason {@code reason}参数
     * @param defaultReason {@code defaultReason}参数
     * @return 处理结果
     */
    private String normalizeActionReason(String reason, String defaultReason) {
        if (reason == null || reason.isBlank()) {
            return defaultReason;
        }
        String normalized = reason.strip().replace('\0', ' ');
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    /**
     * 处理{@code launchAfterCommit}相关逻辑。
     *
     * @param request 请求参数
     */
    private void launchAfterCommit(AgentRunRequest request) {
        afterCommit(() -> executionCoordinator.launchOrMarkFailed(request));
    }

    /**
     * 处理{@code launchResumeAfterCommit}相关逻辑。
     *
     * @param request 请求参数
     */
    private void launchResumeAfterCommit(AgentResumeRequest request) {
        afterCommit(() -> executionCoordinator.launchResumeOrMarkFailed(request));
    }

    /**
     * 处理{@code afterCommit}相关逻辑。
     *
     * @param action {@code action}参数
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 处理{@code afterCommit}相关逻辑。
             */
            @Override
            public void afterCommit() {
                action.run();
            }
        });
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
     * 封装资源授权相关的不可变数据。
     */
    private record ResourceAuthorization(
        AgentVersionBindingRow binding,
        String action,
        AuthorizationDecision decision
    ) {
    }

    /**
     * 封装任务资源Grant相关的不可变数据。
     */
    private record TaskResourceGrant(String resourceType, Long resourceId, String permission) {
    }
}
