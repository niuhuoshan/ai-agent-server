package group.aitools.nhs.platform.task;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.task.domain.AgentTask;
import group.aitools.nhs.platform.task.domain.AgentTaskParticipant;
import group.aitools.nhs.platform.task.domain.AgentTaskResource;
import group.aitools.nhs.platform.task.domain.AgentTaskVersion;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import group.aitools.nhs.platform.task.mapper.AgentTaskVersionMapper;
import group.aitools.nhs.platform.task.mapper.TaskControlMapper;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.task.service.TaskVersionContentHasher;
import group.aitools.nhs.platform.task.web.ConvertConversationToTaskRequest;
import group.aitools.nhs.platform.task.web.CreateTaskRequest;
import group.aitools.nhs.platform.task.web.PutTaskAccessRuleRequest;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import group.aitools.nhs.platform.task.web.TaskResourceRequest;
import group.aitools.nhs.platform.task.web.TaskView;
import group.aitools.nhs.platform.task.web.TaskVersionView;
import group.aitools.nhs.platform.task.web.UpdateTaskRequest;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class TaskApplicationServiceTest {

    private static final CurrentPrincipal OWNER = new CurrentPrincipal(
        101L, "owner", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal MACHINE = new CurrentPrincipal(
        101L, "automation", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private PlatformIdGenerator idGenerator;
    private AgentTaskMapper taskMapper;
    private AgentTaskVersionMapper versionMapper;
    private TaskControlMapper controlMapper;
    private AgentProjectMapper projectMapper;
    private TaskQueryService queryService;
    private TaskApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        idGenerator = mock(PlatformIdGenerator.class);
        taskMapper = mock(AgentTaskMapper.class);
        versionMapper = mock(AgentTaskVersionMapper.class);
        controlMapper = mock(TaskControlMapper.class);
        projectMapper = mock(AgentProjectMapper.class);
        queryService = mock(TaskQueryService.class);
        when(principalProvider.currentPrincipal()).thenReturn(OWNER);
        service = new TaskApplicationService(
            principalProvider, authorizationEnforcer, idGenerator, taskMapper, versionMapper,
            controlMapper, projectMapper, queryService,
            new TaskVersionContentHasher(JsonMapper.builder().build()),
            JsonMapper.builder().build()
        );
    }

    @Test
    void directCreationPersistsOwnerResourcesAndImmutableVersionThenReplays() {
        when(idGenerator.nextId()).thenReturn(500L, 501L, 502L, 503L);
        when(taskMapper.selectByTaskKey(anyString())).thenReturn(null);
        when(taskMapper.insertIfAbsent(any())).thenReturn(1);
        when(controlMapper.insertParticipant(any())).thenReturn(1);
        when(controlMapper.insertResource(any())).thenReturn(1);
        when(versionMapper.insertSnapshot(any())).thenReturn(1);
        when(taskMapper.bindInitialVersion(500L, 503L, 101L)).thenReturn(1);

        TaskMutationResult first = service.create(createRequest("task-a", "idem-1", Map.of()));

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        ArgumentCaptor<AgentTaskParticipant> participantCaptor = ArgumentCaptor.forClass(AgentTaskParticipant.class);
        ArgumentCaptor<AgentTaskResource> resourceCaptor = ArgumentCaptor.forClass(AgentTaskResource.class);
        ArgumentCaptor<AgentTaskVersion> versionCaptor = ArgumentCaptor.forClass(AgentTaskVersion.class);
        verify(taskMapper).insertIfAbsent(taskCaptor.capture());
        verify(controlMapper).insertParticipant(participantCaptor.capture());
        verify(controlMapper).insertResource(resourceCaptor.capture());
        verify(versionMapper).insertSnapshot(versionCaptor.capture());
        AgentTask persisted = taskCaptor.getValue();

        assertFalse(first.replayed());
        assertEquals(503L, first.taskVersionId());
        assertEquals("owner", participantCaptor.getValue().getParticipantType());
        assertEquals("agent_version", resourceCaptor.getValue().getResourceType());
        assertEquals(88L, resourceCaptor.getValue().getResourceId());
        assertEquals(64, versionCaptor.getValue().getContentHash().length());
        assertEquals(1, versionCaptor.getValue().getVersionNo());

        when(taskMapper.selectByTaskKey(anyString())).thenReturn(persisted);
        TaskMutationResult replay = service.create(createRequest("task-a", "idem-1", Map.of()));
        assertTrue(replay.replayed());
        assertEquals(first.task().id(), replay.task().id());
        verify(taskMapper).insertIfAbsent(any());
    }

    @Test
    void sameIdempotencyKeyCannotChangePayload() {
        when(idGenerator.nextId()).thenReturn(500L, 501L, 502L, 503L);
        when(taskMapper.selectByTaskKey(anyString())).thenReturn(null);
        when(taskMapper.insertIfAbsent(any())).thenReturn(1);
        when(controlMapper.insertParticipant(any())).thenReturn(1);
        when(controlMapper.insertResource(any())).thenReturn(1);
        when(versionMapper.insertSnapshot(any())).thenReturn(1);
        when(taskMapper.bindInitialVersion(500L, 503L, 101L)).thenReturn(1);
        service.create(createRequest("task-a", "idem-1", Map.of()));
        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).insertIfAbsent(taskCaptor.capture());

        when(taskMapper.selectByTaskKey(anyString())).thenReturn(taskCaptor.getValue());
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(createRequest("task-b", "idem-1", Map.of()))
        );
        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(taskMapper).insertIfAbsent(any());
    }

    @Test
    void machineCreationUsesTypedOwnershipWithoutHumanParticipantOrHttpPrincipal() {
        when(idGenerator.nextId()).thenReturn(600L, 601L, 602L, 603L, 604L);
        when(taskMapper.selectByTaskKey(anyString())).thenReturn(null);
        when(taskMapper.insertIfAbsent(any())).thenReturn(1);
        when(controlMapper.insertResource(any())).thenReturn(1);
        when(controlMapper.insertAccessRule(any())).thenReturn(1);
        when(versionMapper.insertSnapshot(any())).thenReturn(1);
        when(taskMapper.bindInitialVersion(600L, 602L, 101L)).thenReturn(1);
        CreateTaskRequest base = createRequest("machine-task", "machine-idem-1", Map.of());
        CreateTaskRequest restricted = new CreateTaskRequest(
            base.idempotencyKey(), base.title(), base.objective(), base.background(), base.projectId(),
            base.agentVersionId(), base.workflowVersionId(), "restricted", base.category(),
            base.orchestrationMode(), base.lifecycleLevel(), base.riskLevel(), base.acceptanceMode(),
            base.importance(), base.urgency(), base.startAt(), base.contextSnapshot(), base.resources(),
            base.acceptanceSnapshot(), base.inputSnapshot(), base.budget(), base.externalRefs(), base.tags()
        );

        TaskMutationResult result = service.createAs(
            MACHINE, restricted
        );

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).insertIfAbsent(taskCaptor.capture());
        assertFalse(result.replayed());
        assertEquals(101L, taskCaptor.getValue().getOwnerId());
        assertEquals("service_account", taskCaptor.getValue().getOwnerPrincipalType());
        assertEquals("service_account", result.task().ownerPrincipalType());
        verify(controlMapper, never()).insertParticipant(any());
        ArgumentCaptor<group.aitools.nhs.platform.task.domain.AgentTaskAccessRule> ruleCaptor =
            ArgumentCaptor.forClass(group.aitools.nhs.platform.task.domain.AgentTaskAccessRule.class);
        verify(controlMapper, times(2)).insertAccessRule(ruleCaptor.capture());
        assertTrue(ruleCaptor.getAllValues().stream()
            .allMatch(rule -> "service_account".equals(rule.getSubjectType())
                && Long.valueOf(101L).equals(rule.getSubjectId())));
        verify(principalProvider, never()).currentPrincipal();
        verify(authorizationEnforcer, times(2)).requireAllowed(eq(MACHINE), org.mockito.ArgumentMatchers.argThat(
            context -> !context.userInterfaceOperation()
        ));
    }

    @Test
    void confirmedConversationDraftCreatesTheCompleteTaskAggregate() {
        ConvertConversationToTaskRequest draft = conversationRequest(
            "enterprise_shared",
            List.of(new TaskResourceRequest("tool", 99L, "use", true, "agent", Map.of()))
        );
        String draftHash = service.previewConversationDraftHash(7L, draft);
        when(idGenerator.nextId()).thenReturn(500L, 501L, 502L, 503L, 504L);
        when(taskMapper.selectBySourceConversationId(7L)).thenReturn(null);
        when(taskMapper.selectByTaskKey(anyString())).thenReturn(null);
        when(taskMapper.insertIfAbsent(any())).thenReturn(1);
        when(controlMapper.insertParticipant(any())).thenReturn(1);
        when(controlMapper.insertResource(any())).thenReturn(1);
        when(versionMapper.insertSnapshot(any())).thenReturn(1);
        when(taskMapper.bindInitialVersion(500L, 504L, 101L)).thenReturn(1);

        TaskMutationResult result = service.createFromConversation(7L, draft.withDraftHash(draftHash));

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        ArgumentCaptor<AgentTaskParticipant> participantCaptor = ArgumentCaptor.forClass(AgentTaskParticipant.class);
        ArgumentCaptor<AgentTaskResource> resourceCaptor = ArgumentCaptor.forClass(AgentTaskResource.class);
        ArgumentCaptor<AgentTaskVersion> versionCaptor = ArgumentCaptor.forClass(AgentTaskVersion.class);
        verify(taskMapper).insertIfAbsent(taskCaptor.capture());
        verify(controlMapper).insertParticipant(participantCaptor.capture());
        verify(controlMapper, times(2)).insertResource(resourceCaptor.capture());
        verify(versionMapper).insertSnapshot(versionCaptor.capture());

        assertFalse(result.replayed());
        assertEquals(7L, taskCaptor.getValue().getSourceConversationId());
        assertEquals("owner", participantCaptor.getValue().getParticipantType());
        assertEquals(List.of("agent_version", "tool"), resourceCaptor.getAllValues().stream()
            .map(AgentTaskResource::getResourceType).toList());
        assertEquals(504L, result.taskVersionId());
        assertTrue(versionCaptor.getValue().getResourceSnapshotJson().contains("\"resources\""));
    }

    @Test
    void staleOrMissingDraftHashCannotCreateConversationTask() {
        ConvertConversationToTaskRequest draft = conversationRequest("enterprise_shared", List.of());

        ServiceException missing = assertThrows(
            ServiceException.class,
            () -> service.createFromConversation(7L, draft)
        );
        ServiceException stale = assertThrows(
            ServiceException.class,
            () -> service.createFromConversation(7L, draft.withDraftHash("0".repeat(64)))
        );

        assertEquals(HttpStatus.CONFLICT, missing.getCode());
        assertEquals(HttpStatus.CONFLICT, stale.getCode());
        verify(taskMapper, never()).insertIfAbsent(any());
    }

    @Test
    void confirmationRechecksCurrentResourcePermission() {
        ConvertConversationToTaskRequest draft = conversationRequest(
            "enterprise_shared",
            List.of(new TaskResourceRequest("tool", 99L, "use", true, "agent", Map.of()))
        );
        String draftHash = service.previewConversationDraftHash(7L, draft);
        when(authorizationEnforcer.requireAllowed(eq(OWNER), org.mockito.ArgumentMatchers.argThat(
            context -> "tool".equals(context.resourceType()) && Long.valueOf(99L).equals(context.resourceId())
        ))).thenThrow(new ServiceException("资源权限已撤销", HttpStatus.FORBIDDEN));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.createFromConversation(7L, draft.withDraftHash(draftHash))
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        verify(taskMapper, never()).insertIfAbsent(any());
    }

    @Test
    void sqlToolRequiresItsDatasetQueryGrantInTheTaskSnapshot() {
        when(controlMapper.selectSqlToolDatasetId(99L)).thenReturn("800");
        ConvertConversationToTaskRequest missingDataset = conversationRequest(
            "enterprise_shared",
            List.of(new TaskResourceRequest("tool", 99L, "use", true, "agent", Map.of()))
        );

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.previewConversationDraftHash(7L, missingDataset)
        );
        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());

        ConvertConversationToTaskRequest governed = conversationRequest(
            "enterprise_shared",
            List.of(
                new TaskResourceRequest("tool", 99L, "use", true, "agent", Map.of()),
                new TaskResourceRequest("dataset", 800L, "query", true, "agent", Map.of())
            )
        );
        assertEquals(64, service.previewConversationDraftHash(7L, governed).length());
    }

    @Test
    void updateAppendsVersionAndUsesExpectedCurrentVersion() {
        AgentTask task = task(700L, "ready", 701L);
        when(controlMapper.lockTask(700L)).thenReturn(700L);
        when(taskMapper.selectPlatformTaskById(700L)).thenReturn(task);
        when(controlMapper.selectRelations(700L, 101L, "human")).thenReturn(List.of("OWNER"));
        when(versionMapper.selectNextVersionNo(700L)).thenReturn(2);
        when(idGenerator.nextId()).thenReturn(702L, 703L);
        when(versionMapper.insertSnapshot(any())).thenReturn(1);
        when(taskMapper.updateDefinitionAndVersion(any(), eq(701L))).thenReturn(1);
        when(controlMapper.insertResource(any())).thenReturn(1);

        TaskMutationResult result = service.update(700L, updateRequest("updated"));

        ArgumentCaptor<AgentTaskVersion> versionCaptor = ArgumentCaptor.forClass(AgentTaskVersion.class);
        verify(versionMapper).insertSnapshot(versionCaptor.capture());
        assertEquals(2, versionCaptor.getValue().getVersionNo());
        assertEquals(702L, result.taskVersionId());
        verify(taskMapper).updateDefinitionAndVersion(any(), eq(701L));
        verify(controlMapper).deleteResources(700L);
    }

    @Test
    void versionsAreReturnedNewestFirstWithImmutableSnapshotFields() {
        AgentTaskVersion current = new AgentTaskVersion();
        current.setId(702L);
        current.setTaskId(700L);
        current.setVersionNo(2);
        current.setTitle("updated");
        current.setObjective("objective");
        current.setContentHash("hash-2");
        current.setCreatedBy(101L);
        current.setCreatedAt(LocalDateTime.of(2026, 8, 17, 10, 0));
        when(queryService.get(700L)).thenReturn(TaskView.from(task(700L, "ready", 702L), JsonMapper.builder().build()));
        when(versionMapper.selectVersions(700L, 50)).thenReturn(List.of(current));

        List<TaskVersionView> versions = service.versions(700L, 50);

        assertEquals(1, versions.size());
        assertEquals(702L, versions.getFirst().id());
        assertEquals("hash-2", versions.getFirst().contentHash());
        assertEquals(101L, versions.getFirst().createdBy());
        verify(versionMapper).selectVersions(700L, 50);
    }

    @Test
    void runningTaskCannotBeEditedAndInvalidManualTransitionIsRejected() {
        AgentTask running = task(710L, "running", 711L);
        when(controlMapper.lockTask(710L)).thenReturn(710L);
        when(taskMapper.selectPlatformTaskById(710L)).thenReturn(running);
        when(controlMapper.selectRelations(710L, 101L, "human")).thenReturn(List.of("OWNER"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(
            ServiceException.class, () -> service.update(710L, updateRequest("updated"))
        ).getCode());
        verify(versionMapper, never()).insertSnapshot(any());

        AgentTask ready = task(720L, "ready", 721L);
        when(controlMapper.lockTask(720L)).thenReturn(720L);
        when(taskMapper.selectPlatformTaskById(720L)).thenReturn(ready);
        when(controlMapper.selectRelations(720L, 101L, "human")).thenReturn(List.of("OWNER"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(
            ServiceException.class, () -> service.updateStatus(720L, "rework")
        ).getCode());
        verify(taskMapper, never()).updateStatus(anyLong(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void ownerCannotBeRemovedOrExplicitlyDeniedAndSecretsAreRejected() {
        AgentTask task = task(730L, "ready", 731L);
        when(controlMapper.lockTask(730L)).thenReturn(730L);
        when(taskMapper.selectPlatformTaskById(730L)).thenReturn(task);
        when(controlMapper.selectRelations(730L, 101L, "human")).thenReturn(List.of("OWNER"));

        assertEquals(HttpStatus.CONFLICT, assertThrows(
            ServiceException.class,
            () -> service.removeParticipant(730L, 101L, "assignee")
        ).getCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(
            ServiceException.class,
            () -> service.putAccessRule(730L, new PutTaskAccessRuleRequest(
                "user", 101L, null, "view", "deny", null
            ))
        ).getCode());

        ServiceException secret = assertThrows(
            ServiceException.class,
            () -> service.create(createRequest(
                "task-secret", "idem-secret", Map.of("credentialToken", "raw-secret")
            ))
        );
        assertEquals(HttpStatus.BAD_REQUEST, secret.getCode());
        verify(taskMapper, never()).insertIfAbsent(any());
    }

    private CreateTaskRequest createRequest(
        String title,
        String idempotencyKey,
        Map<String, Object> context
    ) {
        return new CreateTaskRequest(
            idempotencyKey, title, "objective", null, null, 88L, null,
            "enterprise_shared", "general", "single_agent", "L1_short_task",
            "R1", "human", 1, 1, null, context, List.of(),
            Map.of("mode", "human"), Map.of(), Map.of(), Map.of(), List.of("phase-1")
        );
    }

    private UpdateTaskRequest updateRequest(String title) {
        return new UpdateTaskRequest(
            title, "updated objective", null, null, 88L, null,
            "enterprise_shared", "general", "single_agent", "L1_short_task",
            "R1", "human", 1, 0, null, Map.of(), List.of(),
            Map.of("mode", "human"), Map.of(), Map.of(), Map.of(), List.of()
        );
    }

    private ConvertConversationToTaskRequest conversationRequest(
        String visibility,
        List<TaskResourceRequest> resources
    ) {
        return new ConvertConversationToTaskRequest(
            "conversation-1", "Conversation task", "objective", null,
            null, 88L, null, visibility, "general", "single_agent", "L1_short_task",
            "R1", "human", 1, 0, null, Map.of("selectedMessages", List.of(1L)), resources,
            Map.of("mode", "human"), Map.of(), Map.of(), Map.of(), List.of("phase-1"), null
        );
    }

    private AgentTask task(Long id, String status, Long currentVersionId) {
        AgentTask task = new AgentTask();
        task.setId(id);
        task.setTaskKey("T-" + id);
        task.setTitle("task");
        task.setObjective("objective");
        task.setStatus(status);
        task.setOwnerId(101L);
        task.setOwnerPrincipalType("human");
        task.setCurrentVersionId(currentVersionId);
        task.setVisibility("enterprise_shared");
        task.setCategory("general");
        task.setOrchestrationMode("single_agent");
        task.setLifecycleLevel("L1_short_task");
        task.setRiskLevel("R1");
        task.setImportance(0);
        task.setUrgency(0);
        task.setAcceptanceMode("human");
        task.setContextSnapshotJson("{}");
        task.setAcceptanceConfigJson("{}");
        task.setBudgetJson("{}");
        task.setExternalRefsJson("{}");
        task.setTagsJson("[]");
        task.setCreateTime(LocalDateTime.now());
        task.setDelFlag("0");
        return task;
    }
}
