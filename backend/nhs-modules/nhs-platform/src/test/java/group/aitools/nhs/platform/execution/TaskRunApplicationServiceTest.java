package group.aitools.nhs.platform.execution;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.agent.service.AgentVersionContentHasher;
import group.aitools.nhs.platform.approval.domain.AgentApprovalRequest;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.domain.AgentRunStep;
import group.aitools.nhs.platform.execution.domain.AgentTaskRun;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.persistence.row.TaskRunDefinitionRow;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.execution.service.TaskRunExecutionCoordinator;
import group.aitools.nhs.platform.execution.service.TaskRunSnapshotFactory;
import group.aitools.nhs.platform.execution.web.CreateTaskRunRequest;
import group.aitools.nhs.platform.execution.web.RetryTaskRunRequest;
import group.aitools.nhs.platform.execution.web.TaskRunActionResult;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.DecisionEvidence;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PermissionSource;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.task.service.TaskVersionContentHasher;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class TaskRunApplicationServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal COLLIDING_SERVICE = new CurrentPrincipal(
        101L, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final TaskVersionContentHasher taskHasher = new TaskVersionContentHasher(jsonMapper);
    private final AgentVersionContentHasher agentHasher = new AgentVersionContentHasher(jsonMapper);

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private PlatformIdGenerator idGenerator;
    private TaskRunCommandMapper runMapper;
    private TaskRunExecutionCoordinator coordinator;
    private TaskQueryService taskQueryService;
    private TaskRunApplicationService service;
    private TaskRunDefinitionRow definition;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        idGenerator = mock(PlatformIdGenerator.class);
        runMapper = mock(TaskRunCommandMapper.class);
        coordinator = mock(TaskRunExecutionCoordinator.class);
        taskQueryService = mock(TaskQueryService.class);
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        when(authorizationEnforcer.requireAllowed(eq(MEMBER), any())).thenReturn(allow("allowed"));
        when(runMapper.selectRelations(10L, 101L, "human")).thenReturn(List.of("OWNER"));
        definition = definition(List.of());
        when(runMapper.selectDefinition(10L)).thenReturn(definition);
        when(runMapper.selectBindings(200L)).thenReturn(List.of());
        when(runMapper.selectNextAttempt(10L)).thenReturn(1);
        when(runMapper.insertRun(any())).thenReturn(1);
        when(runMapper.insertStep(any())).thenReturn(1);
        when(runMapper.bindLatestRun(10L, 100L, 500L, 101L)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(500L, 501L);
        service = new TaskRunApplicationService(
            principalProvider,
            authorizationEnforcer,
            idGenerator,
            runMapper,
            new TaskRunSnapshotFactory(jsonMapper),
            coordinator,
            taskHasher,
            agentHasher,
            taskQueryService,
            jsonMapper
        );
    }

    @Test
    void createsFrozenRunAndSingleAgentStep() {
        TaskRunActionResult result = service.create(
            10L, new CreateTaskRunRequest("request-1", "Prepare the accepted report")
        );

        assertFalse(result.replayed());
        assertEquals(500L, result.run().id());
        assertEquals("queued", result.run().status());
        ArgumentCaptor<AgentTaskRun> runCaptor = ArgumentCaptor.forClass(AgentTaskRun.class);
        ArgumentCaptor<AgentRunStep> stepCaptor = ArgumentCaptor.forClass(AgentRunStep.class);
        verify(runMapper).insertRun(runCaptor.capture());
        verify(runMapper).insertStep(stepCaptor.capture());

        AgentTaskRun persisted = runCaptor.getValue();
        AgentRunRequest runtime = jsonMapper.readValue(
            persisted.getRuntimeSnapshotJson(), AgentRunRequest.class
        );
        assertEquals(10L, runtime.taskId());
        assertEquals(100L, ((Number) runtime.attributes().get("taskVersionId")).longValue());
        assertEquals(200L, runtime.agentVersionId());
        assertEquals("Prepare the accepted report", runtime.input());
        assertEquals("env:MODEL_KEY", runtime.model().credentialRef());
        assertEquals("none", runtime.authorizationSnapshot().get("workspaceAccess"));
        assertFalse(persisted.getRuntimeSnapshotJson().contains("raw-api-key"));
        assertEquals(501L, stepCaptor.getValue().getId());
        assertEquals(200L, stepCaptor.getValue().getAgentVersionId());
    }

    @Test
    void sameIdempotencyKeyWithDifferentInputIsRejected() {
        service.create(10L, new CreateTaskRunRequest("request-1", "first input"));
        ArgumentCaptor<AgentTaskRun> runCaptor = ArgumentCaptor.forClass(AgentTaskRun.class);
        verify(runMapper).insertRun(runCaptor.capture());
        when(runMapper.selectByTrace(eq(10L), any())).thenReturn(runCaptor.getValue());

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(10L, new CreateTaskRunRequest("request-1", "different input"))
        );

        assertTrue(exception.getMessage().contains("同一幂等键"));
    }

    @Test
    void explicitResourceDenyPreventsRunInsertion() {
        AgentVersionBindingRow binding = binding("tool", 900L, "use", "{}");
        definition = definition(List.of(binding));
        when(runMapper.selectDefinition(10L)).thenReturn(definition);
        when(runMapper.selectBindings(200L)).thenReturn(List.of(binding));
        when(authorizationEnforcer.decide(eq(MEMBER), any())).thenReturn(deny("EXPLICIT_DENY"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(10L, new CreateTaskRunRequest("request-2", "input"))
        );

        assertTrue(exception.getMessage().contains("没有执行权限"));
        verify(runMapper, never()).insertRun(any());
    }

    @Test
    void approvalRequiredToolIsFrozenInsteadOfBeingSilentlyDenied() {
        AgentVersionBindingRow binding = binding("tool", 900L, "use", "{}");
        definition = definition(List.of(binding));
        when(runMapper.selectDefinition(10L)).thenReturn(definition);
        when(runMapper.selectBindings(200L)).thenReturn(List.of(binding));
        when(authorizationEnforcer.decide(eq(MEMBER), any())).thenReturn(approvalRequired());

        TaskRunActionResult result = service.create(
            10L, new CreateTaskRunRequest("request-approval", "input")
        );

        assertFalse(result.replayed());
        ArgumentCaptor<AgentTaskRun> runCaptor = ArgumentCaptor.forClass(AgentTaskRun.class);
        verify(runMapper).insertRun(runCaptor.capture());
        assertTrue(runCaptor.getValue().getAuthorizationSnapshotJson().contains("approval_required"));
    }

    @Test
    void agentBindingMissingFromTaskResourceSnapshotIsRejectedBeforeRunCreation() {
        AgentVersionBindingRow binding = binding("tool", 900L, "use", "{}");
        definition = definition(List.of(binding));
        definition.setTaskResourceSnapshotJson("{\"agentVersionId\":200,\"resources\":[]}");
        definition.setTaskContentHash(taskHasher.hash(
            definition.getTaskVersionTitle(), definition.getTaskVersionObjective(),
            definition.getTaskContextSnapshotJson(), definition.getTaskResourceSnapshotJson(),
            definition.getTaskAcceptanceSnapshotJson(), definition.getTaskInputSnapshotJson()
        ));
        when(runMapper.selectDefinition(10L)).thenReturn(definition);
        when(runMapper.selectBindings(200L)).thenReturn(List.of(binding));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(10L, new CreateTaskRunRequest("missing-resource", "input"))
        );

        assertEquals(403, exception.getCode());
        assertTrue(exception.getMessage().contains("未授权Agent能力"));
        verify(runMapper, never()).insertRun(any());
    }

    @Test
    void tamperedTaskVersionHashIsRejectedBeforeAuthorizationCanBecomeExecution() {
        definition.setTaskContentHash("0".repeat(64));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(10L, new CreateTaskRunRequest("request-3", "input"))
        );

        assertTrue(exception.getMessage().contains("任务版本内容哈希不一致"));
        verify(runMapper, never()).insertRun(any());
    }

    @Test
    void tamperedAgentVersionHashIsRejected() {
        definition.setAgentContentHash("f".repeat(64));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(10L, new CreateTaskRunRequest("request-4", "input"))
        );

        assertTrue(exception.getMessage().contains("Agent版本内容哈希不一致"));
        verify(runMapper, never()).insertRun(any());
    }

    @Test
    void futureTaskCannotBeStartedEarly() {
        definition.setStartAt(LocalDateTime.now().plusHours(1));
        AgentTaskRun run = run("queued", runtimeJson("input"));
        when(runMapper.selectRun(10L, 500L)).thenReturn(run);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.start(10L, 500L)
        );

        assertTrue(exception.getMessage().contains("尚未到开始时间"));
        verify(runMapper, never()).claimRun(anyLong(), anyLong(), any());
    }

    @Test
    void serviceAccountCannotStartHumanRunWithSameNumericId() {
        AgentTaskRun run = run("queued", runtimeJson("input"));
        when(runMapper.selectRun(10L, 500L)).thenReturn(run);
        when(runMapper.selectDefinition(10L)).thenReturn(definition);

        ServiceException exception = assertThrows(ServiceException.class, () ->
            service.startAs(COLLIDING_SERVICE, 10L, 500L, definition.getTaskVersionId())
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        verify(runMapper, never()).claimRun(any(), any(), any());
    }

    @Test
    void atomicClaimLaunchesExactlyOnceAndSecondStartOnlyReplays() {
        AgentTaskRun queued = run("queued", runtimeJson("input"));
        AgentTaskRun running = run("running", queued.getRuntimeSnapshotJson());
        when(runMapper.selectRun(10L, 500L)).thenReturn(queued, running, running);
        when(coordinator.available()).thenReturn(true);
        when(coordinator.workerId()).thenReturn("worker-1");
        when(runMapper.claimRun(10L, 500L, "worker-1")).thenReturn(1);
        when(runMapper.startStep(500L, 501L)).thenReturn(1);

        TaskRunActionResult first = service.start(10L, 500L);
        TaskRunActionResult second = service.start(10L, 500L);

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        verify(coordinator).launchOrMarkFailed(any(AgentRunRequest.class));
        verify(runMapper).claimRun(10L, 500L, "worker-1");
    }

    @Test
    void staleRunningLeaseIsReclaimedButLocallyActiveRunIsNotDuplicated() {
        AgentTaskRun stale = run("running", runtimeJson("input"));
        stale.setLeaseUntil(LocalDateTime.now().minusMinutes(1));
        AgentTaskRun reclaimed = run("running", stale.getRuntimeSnapshotJson());
        reclaimed.setLeaseUntil(LocalDateTime.now().plusMinutes(30));
        when(runMapper.selectRun(10L, 500L)).thenReturn(stale, reclaimed);
        when(coordinator.available()).thenReturn(true);
        when(coordinator.workerId()).thenReturn("worker-reclaimer");
        when(coordinator.isLocallyActive(500L)).thenReturn(false);
        when(runMapper.claimRun(10L, 500L, "worker-reclaimer")).thenReturn(1);
        when(runMapper.startStep(500L, 501L)).thenReturn(1);

        TaskRunActionResult result = service.start(10L, 500L);

        assertFalse(result.replayed());
        verify(runMapper).claimRun(10L, 500L, "worker-reclaimer");
        verify(coordinator).launchOrMarkFailed(any(AgentRunRequest.class));
    }

    @Test
    void expiredLeaseStillReplaysWhenInvocationIsActiveOnThisWorker() {
        AgentTaskRun stale = run("running", runtimeJson("input"));
        stale.setLeaseUntil(LocalDateTime.now().minusMinutes(1));
        when(runMapper.selectRun(10L, 500L)).thenReturn(stale);
        when(coordinator.available()).thenReturn(true);
        when(coordinator.isLocallyActive(500L)).thenReturn(true);

        TaskRunActionResult result = service.start(10L, 500L);

        assertTrue(result.replayed());
        verify(runMapper, never()).claimRun(anyLong(), anyLong(), any());
    }

    @Test
    void pausePersistsStateBeforeInterruptingRuntime() {
        AgentTaskRun running = run("running", runtimeJson("input"));
        AgentTaskRun paused = run("paused", running.getRuntimeSnapshotJson());
        paused.setWaitReason("maintenance");
        when(runMapper.selectRun(10L, 500L)).thenReturn(running, paused);
        when(runMapper.pauseRun(10L, 500L, "maintenance")).thenReturn(1);

        TaskRunActionResult result = service.pause(10L, 500L, "maintenance");

        assertEquals("paused", result.run().status());
        verify(runMapper).markStepWaiting(500L, 501L);
        verify(runMapper).markTaskBlocked(10L, 500L);
        verify(coordinator).requestCancellation(any(AgentRunRequest.class), eq("maintenance"));
    }

    @Test
    void manualResumeUsesPersistedRuntimeIdentityAndNoClientAction() {
        AgentTaskRun paused = run("paused", runtimeJson("input"));
        AgentTaskRun running = run("running", paused.getRuntimeSnapshotJson());
        when(runMapper.selectRun(10L, 500L)).thenReturn(paused, running);
        when(coordinator.available()).thenReturn(true);
        when(coordinator.workerId()).thenReturn("worker-resume");
        when(runMapper.claimResumedRun(10L, 500L, "worker-resume")).thenReturn(1);
        when(runMapper.startStep(500L, 501L)).thenReturn(1);

        TaskRunActionResult result = service.resume(10L, 500L);

        assertFalse(result.replayed());
        ArgumentCaptor<group.aitools.nhs.runtime.spi.AgentResumeRequest> captor =
            ArgumentCaptor.forClass(group.aitools.nhs.runtime.spi.AgentResumeRequest.class);
        verify(coordinator).launchResumeOrMarkFailed(captor.capture());
        assertEquals(group.aitools.nhs.runtime.spi.RuntimeResumeMode.CONTINUE, captor.getValue().mode());
        assertTrue(captor.getValue().pendingAction().isEmpty());
        assertEquals(101L, captor.getValue().userId());
    }

    @Test
    void approvalResumeUsesOriginalRunIdentityAndEveryServerOwnedAction() {
        AgentTaskRun waiting = run("waiting_approval", runtimeJson("input"));
        AgentTaskRun running = run("running", waiting.getRuntimeSnapshotJson());
        when(runMapper.selectRun(10L, 500L)).thenReturn(waiting, running);
        when(coordinator.available()).thenReturn(true);
        when(coordinator.workerId()).thenReturn("worker-approval");
        when(runMapper.claimApprovedRun(10L, 500L, "worker-approval")).thenReturn(1);
        when(runMapper.startStep(500L, 501L)).thenReturn(1);
        AgentApprovalRequest approval = approval(501L);
        CurrentPrincipal reviewer = new CurrentPrincipal(
            701L, "approver", PrincipalType.HUMAN, Set.of(PlatformRole.APPROVAL_USER)
        );
        List<Map<String, Object>> actions = List.of(
            Map.of("id", "call-a", "name", "send", "input", Map.of("to", "ops")),
            Map.of("id", "call-b", "name", "update", "input", Map.of("ticket", "T-1"))
        );

        service.resumeFromApproval(approval, reviewer, actions);

        ArgumentCaptor<group.aitools.nhs.runtime.spi.AgentResumeRequest> captor =
            ArgumentCaptor.forClass(group.aitools.nhs.runtime.spi.AgentResumeRequest.class);
        verify(coordinator).launchResumeOrMarkFailed(captor.capture());
        assertEquals(group.aitools.nhs.runtime.spi.RuntimeResumeMode.APPROVAL, captor.getValue().mode());
        assertEquals(group.aitools.nhs.runtime.spi.RuntimeResumeDecision.APPROVE, captor.getValue().decision());
        assertEquals(List.of("call-a", "call-b"), captor.getValue().pendingActions().stream()
            .map(action -> action.get("id")).toList());
        assertEquals(101L, captor.getValue().userId());
        assertEquals(701L, captor.getValue().decisionMetadata().get("reviewerId"));
    }

    @Test
    void approvalForDifferentStepCannotResumeRun() {
        AgentTaskRun waiting = run("waiting_approval", runtimeJson("input"));
        when(runMapper.selectRun(10L, 500L)).thenReturn(waiting);
        when(coordinator.available()).thenReturn(true);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.resumeFromApproval(
                approval(999L),
                new CurrentPrincipal(
                    701L, "approver", PrincipalType.HUMAN, Set.of(PlatformRole.APPROVAL_USER)
                ),
                List.of(Map.of("id", "call-a", "name", "send"))
            )
        );

        assertTrue(exception.getMessage().contains("身份"));
        verify(runMapper, never()).claimApprovedRun(anyLong(), anyLong(), any());
        verify(coordinator, never()).launchResumeOrMarkFailed(any());
    }

    @Test
    void rejectionFailsWaitingRunWithoutInvokingRuntime() {
        AgentTaskRun waiting = run("waiting_approval", runtimeJson("input"));
        AgentTaskRun failed = run("failed", waiting.getRuntimeSnapshotJson());
        when(runMapper.selectRun(10L, 500L)).thenReturn(waiting, failed);
        when(runMapper.failRun(500L, "APPROVAL_REJECTED", "高风险工具调用审批被拒绝"))
            .thenReturn(1);
        when(runMapper.failStep(
            500L, 501L, "APPROVAL_REJECTED", "高风险工具调用审批被拒绝"
        )).thenReturn(1);

        service.rejectFromApproval(approval(501L));

        verify(coordinator, never()).launchResumeOrMarkFailed(any());
        verify(runMapper).failRun(500L, "APPROVAL_REJECTED", "高风险工具调用审批被拒绝");
    }

    @Test
    void expirationEndsWaitingRunAndStep() {
        AgentTaskRun waiting = run("waiting_approval", runtimeJson("input"));
        AgentTaskRun expired = run("expired", waiting.getRuntimeSnapshotJson());
        when(runMapper.selectRun(10L, 500L)).thenReturn(waiting, expired);
        when(runMapper.expireApprovalRun(10L, 500L)).thenReturn(1);
        when(runMapper.failStep(500L, 501L, "APPROVAL_EXPIRED", "工具审批已过期"))
            .thenReturn(1);

        service.expireFromApproval(approval(501L));

        verify(runMapper).expireApprovalRun(10L, 500L);
        verify(runMapper).failStep(500L, 501L, "APPROVAL_EXPIRED", "工具审批已过期");
        verify(coordinator, never()).launchResumeOrMarkFailed(any());
    }

    @Test
    void retryClonesLatestTerminalRunThroughNormalAuthorizationPath() {
        definition.setTaskStatus("blocked");
        definition.setLatestRunId(500L);
        definition.setLatestRunStatus("failed");
        AgentTaskRun failed = run("failed", runtimeJson("same frozen input"));
        when(runMapper.selectRun(10L, 500L)).thenReturn(failed);
        when(idGenerator.nextId()).thenReturn(600L, 601L);
        when(runMapper.bindLatestRun(10L, 100L, 600L, 101L)).thenReturn(1);

        TaskRunActionResult result = service.retry(
            10L, 500L, new RetryTaskRunRequest("retry-key", false)
        );

        assertEquals(600L, result.run().id());
        ArgumentCaptor<AgentTaskRun> captor = ArgumentCaptor.forClass(AgentTaskRun.class);
        verify(runMapper).insertRun(captor.capture());
        assertEquals(500L, captor.getValue().getParentRunId());
        assertTrue(captor.getValue().getRuntimeSnapshotJson().contains("same frozen input"));
    }

    private TaskRunDefinitionRow definition(List<AgentVersionBindingRow> bindings) {
        TaskRunDefinitionRow row = new TaskRunDefinitionRow();
        row.setTaskId(10L);
        row.setTaskVersionId(100L);
        row.setOwnerId(101L);
        row.setTaskStatus("ready");
        row.setTaskTitle("Task");
        row.setTaskObjective("Objective");
        row.setTaskVersionTitle("Task");
        row.setTaskVersionObjective("Objective");
        row.setTaskContextSnapshotJson("{\"selected\":[2,1]}");
        List<Map<String, Object>> taskResources = bindings.stream().map(binding -> Map.<String, Object>of(
            "resourceType", binding.getResourceType(),
            "resourceId", binding.getResourceId(),
            "permission", "knowledge_base".equals(binding.getResourceType()) ? "read" : "use"
        )).toList();
        row.setTaskResourceSnapshotJson(jsonMapper.writeValueAsString(Map.of(
            "agentVersionId", 200,
            "resources", taskResources
        )));
        row.setTaskAcceptanceSnapshotJson("{\"mode\":\"human\"}");
        row.setTaskInputSnapshotJson("{}");
        row.setTaskBudgetJson("{}");
        row.setTaskContentHash(taskHasher.hash(
            row.getTaskVersionTitle(), row.getTaskVersionObjective(),
            row.getTaskContextSnapshotJson(), row.getTaskResourceSnapshotJson(),
            row.getTaskAcceptanceSnapshotJson(), row.getTaskInputSnapshotJson()
        ));
        row.setAgentVersionId(200L);
        row.setAgentId(20L);
        row.setAgentKey("report-agent");
        row.setAgentName("Report Agent");
        row.setAgentStatus("active");
        row.setAgentVersionStatus("published");
        row.setAgentPublishedAt(LocalDateTime.now().minusDays(1));
        row.setSystemPrompt("Follow the task snapshot.");
        row.setModelId(300L);
        row.setAgentRuntimeConfigJson("""
            {"maxIterations":4,"workspaceAccess":"none","modelSnapshot":{
              "modelId":300,"provider":"openai-compatible","modelName":"test-model",
              "endpointUrl":"https://model.example/v1","credentialRef":"env:MODEL_KEY",
              "contextSize":8192,"maxOutputTokens":2048,
              "reasoningConfig":{"temperature":0.2},"capabilities":{"streaming":true}
            }}
            """);
        row.setAgentWelcomeConfigJson("{}");
        row.setAgentRoutingTagsJson("[]");
        AgentDefinitionVersion version = new AgentDefinitionVersion();
        version.setId(200L);
        version.setAgentId(20L);
        version.setSystemPrompt(row.getSystemPrompt());
        version.setModelId(300L);
        version.setRuntimeConfigJson(row.getAgentRuntimeConfigJson());
        version.setWelcomeConfigJson(row.getAgentWelcomeConfigJson());
        version.setRoutingTagsJson(row.getAgentRoutingTagsJson());
        row.setAgentContentHash(agentHasher.hash(version, bindings));
        return row;
    }

    private AgentVersionBindingRow binding(String type, Long id, String permission, String config) {
        AgentVersionBindingRow row = new AgentVersionBindingRow();
        row.setId(id + 1);
        row.setResourceType(type);
        row.setResourceId(id);
        row.setPermission(permission);
        row.setConfigJson(config);
        return row;
    }

    private AgentTaskRun run(String status, String runtimeJson) {
        AgentTaskRun run = new AgentTaskRun();
        run.setId(500L);
        run.setTaskId(10L);
        run.setTaskVersionId(100L);
        run.setTraceId("a".repeat(64));
        run.setStatus(status);
        run.setAttemptNo(1);
        run.setRuntimeSnapshotJson(runtimeJson);
        run.setCreatedBy(101L);
        run.setCreatedAt(LocalDateTime.now());
        return run;
    }

    private AgentApprovalRequest approval(Long stepId) {
        AgentApprovalRequest approval = new AgentApprovalRequest();
        approval.setId(42L);
        approval.setTaskId(10L);
        approval.setRunId(500L);
        approval.setStepId(stepId);
        approval.setReplyId("reply-1");
        return approval;
    }

    private String runtimeJson(String input) {
        AgentRunRequest request = new TaskRunSnapshotFactory(jsonMapper).create(
            definition,
            MEMBER,
            500L,
            501L,
            "a".repeat(64),
            input,
            Map.of(
                "workspaceAccess", "none",
                "principalId", MEMBER.id(),
                "principalType", "human"
            ),
            List.of()
        ).request();
        return jsonMapper.writeValueAsString(request);
    }

    private AuthorizationDecision allow(String code) {
        return decision(PermissionEffect.ALLOW, code);
    }

    private AuthorizationDecision deny(String code) {
        return decision(PermissionEffect.DENY, code);
    }

    private AuthorizationDecision approvalRequired() {
        return decision(PermissionEffect.APPROVAL_REQUIRED, "APPROVAL_REQUIRED");
    }

    private AuthorizationDecision decision(PermissionEffect effect, String code) {
        return new AuthorizationDecision(
            effect,
            code,
            code,
            List.of(new DecisionEvidence(PermissionSource.PROFILE, "test", effect, code))
        );
    }
}
