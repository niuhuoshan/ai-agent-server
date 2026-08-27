package group.aitools.nhs.platform.artifact;

import group.aitools.nhs.platform.artifact.domain.AgentAcceptanceRecord;
import group.aitools.nhs.platform.artifact.domain.AgentArtifact;
import group.aitools.nhs.platform.artifact.mapper.ArtifactAcceptanceMapper;
import group.aitools.nhs.platform.artifact.persistence.row.AcceptanceTaskRow;
import group.aitools.nhs.platform.artifact.service.AcceptanceApplicationService;
import group.aitools.nhs.platform.artifact.web.AcceptanceDecisionRequest;
import group.aitools.nhs.platform.artifact.web.AcceptanceDecisionResult;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AcceptanceApplicationServiceTest {

    private static final CurrentPrincipal OWNER = new CurrentPrincipal(
        101L, "owner", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal OTHER_REVIEWER = new CurrentPrincipal(
        102L, "other", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal MACHINE_REVIEWER = new CurrentPrincipal(
        101L, "acceptance-bot", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );

    private CurrentPrincipalProvider principalProvider;
    private ArtifactAcceptanceMapper artifactMapper;
    private TaskRunCommandMapper runMapper;
    private AcceptanceApplicationService service;
    private AcceptanceTaskRow task;
    private AgentArtifact artifact;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        artifactMapper = mock(ArtifactAcceptanceMapper.class);
        runMapper = mock(TaskRunCommandMapper.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        task = task("verifying", "succeeded", "human", 500L);
        artifact = artifact(700L, "document");
        when(principalProvider.currentPrincipal()).thenReturn(OWNER);
        when(runMapper.selectRelations(10L, 101L, "human")).thenReturn(List.of("OWNER"));
        when(runMapper.selectRelations(10L, 102L, "human")).thenReturn(List.of("OWNER"));
        when(artifactMapper.selectAcceptanceTask(10L, 500L)).thenReturn(task);
        when(artifactMapper.selectAvailableArtifacts(eq(10L), eq(500L), any())).thenReturn(List.of(artifact));
        when(artifactMapper.insertAcceptance(any())).thenReturn(1);
        when(artifactMapper.transitionTask(any(), any(), any(), anyString(), any())).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(800L);
        service = new AcceptanceApplicationService(
            principalProvider,
            mock(AuthorizationEnforcer.class),
            artifactMapper,
            runMapper,
            mock(TaskQueryService.class),
            idGenerator,
            JsonMapper.builder().build(),
            mock(NotificationApplicationService.class)
        );
    }

    @Test
    void passingLatestSuccessfulRunCompletesTaskWithImmutableArtifactIds() {
        AcceptanceDecisionResult result = service.decide(10L, 500L, request("accept-1", "passed", Map.of()));

        assertEquals("completed", result.taskStatus());
        assertFalse(result.replayed());
        ArgumentCaptor<AgentAcceptanceRecord> captor = ArgumentCaptor.forClass(AgentAcceptanceRecord.class);
        verify(artifactMapper).insertAcceptance(captor.capture());
        AgentAcceptanceRecord record = captor.getValue();
        assertEquals("[700]", record.getArtifactIdsJson());
        assertEquals(101L, record.getReviewerId());
        assertEquals("human", record.getReviewerPrincipalType());
        assertEquals(64, record.getIdempotencyKeyHash().length());
        assertEquals(64, record.getRequestHash().length());
        verify(artifactMapper).transitionTask(10L, 500L, List.of("verifying"), "completed", 101L);
    }

    @Test
    void machineAcceptanceIsTypedAndDoesNotConsultHttpPrincipal() {
        when(runMapper.selectRelations(10L, 101L, "service_account")).thenReturn(List.of("OWNER"));

        AcceptanceDecisionResult result = service.decideAs(
            MACHINE_REVIEWER, 10L, 500L, request("machine-accept-1", "passed", Map.of())
        );

        ArgumentCaptor<AgentAcceptanceRecord> captor = ArgumentCaptor.forClass(AgentAcceptanceRecord.class);
        verify(artifactMapper).insertAcceptance(captor.capture());
        assertEquals("completed", result.taskStatus());
        assertEquals(101L, captor.getValue().getReviewerId());
        assertEquals("service_account", captor.getValue().getReviewerPrincipalType());
        verify(principalProvider, never()).currentPrincipal();
    }

    @Test
    void passingWithoutAvailableOrRequiredArtifactIsRejected() {
        when(artifactMapper.selectAvailableArtifacts(eq(10L), eq(500L), any())).thenReturn(List.of());
        assertThrows(ServiceException.class, () ->
            service.decide(10L, 500L, request("missing-1", "passed", Map.of()))
        );

        when(artifactMapper.selectAvailableArtifacts(eq(10L), eq(500L), any())).thenReturn(List.of(artifact(700L, "file")));
        assertThrows(ServiceException.class, () ->
            service.decide(10L, 500L, request("missing-2", "passed", Map.of()))
        );
        verify(artifactMapper, never()).insertAcceptance(any());
    }

    @Test
    void passingFailedOrNonLatestRunIsRejected() {
        task.setRunStatus("failed");
        assertThrows(ServiceException.class, () ->
            service.decide(10L, 500L, request("failed-run", "passed", Map.of()))
        );

        task.setRunStatus("succeeded");
        task.setLatestRunId(501L);
        assertThrows(ServiceException.class, () ->
            service.decide(10L, 500L, request("old-run", "passed", Map.of()))
        );
        verify(artifactMapper, never()).insertAcceptance(any());
    }

    @Test
    void pendingApprovalAndFailedRuleBlockCompletion() {
        when(artifactMapper.countPendingApprovals(10L, 500L)).thenReturn(1);
        assertThrows(ServiceException.class, () ->
            service.decide(10L, 500L, request("pending-approval", "passed", Map.of()))
        );

        when(artifactMapper.countPendingApprovals(10L, 500L)).thenReturn(0);
        task.setAcceptanceMode("combined");
        assertThrows(ServiceException.class, () ->
            service.decide(10L, 500L, request("failed-rule", "passed", Map.of("passed", false)))
        );
        verify(artifactMapper, never()).insertAcceptance(any());
    }

    @Test
    void sameReviewerAndRequestReplaysButChangedPayloadOrReviewerConflicts() {
        AcceptanceDecisionRequest original = request("stable-key", "passed", Map.of());
        service.decide(10L, 500L, original);
        ArgumentCaptor<AgentAcceptanceRecord> captor = ArgumentCaptor.forClass(AgentAcceptanceRecord.class);
        verify(artifactMapper).insertAcceptance(captor.capture());
        when(artifactMapper.selectAcceptanceByKey(anyString())).thenReturn(captor.getValue());

        AcceptanceDecisionResult replay = service.decide(10L, 500L, original);
        assertTrue(replay.replayed());
        verify(artifactMapper, times(1)).insertAcceptance(any());

        assertThrows(ServiceException.class, () ->
            service.decide(10L, 500L, new AcceptanceDecisionRequest(
                "stable-key", List.of(700L), "passed", "changed", Map.of()
            ))
        );
        assertThrows(ServiceException.class, () ->
            service.decideAs(MACHINE_REVIEWER, 10L, 500L, original)
        );
        when(principalProvider.currentPrincipal()).thenReturn(OTHER_REVIEWER);
        assertThrows(ServiceException.class, () -> service.decide(10L, 500L, original));
        verify(artifactMapper, times(1)).insertAcceptance(any());
    }

    @Test
    void reworkTransitionsTaskAndCompletedTaskCannotReceiveNewDecision() {
        AcceptanceDecisionResult rework = service.decide(
            10L, 500L, request("rework-1", "rework", Map.of())
        );
        assertEquals("rework", rework.taskStatus());
        verify(artifactMapper).transitionTask(
            10L, 500L, List.of("verifying", "blocked"), "rework", 101L
        );

        task.setTaskStatus("completed");
        assertThrows(ServiceException.class, () ->
            service.decide(10L, 500L, request("after-completion", "passed", Map.of()))
        );
    }

    @Test
    void nestedSecretsAndExcessiveDepthAreRejectedBeforeDatabaseMutation() {
        assertThrows(ServiceException.class, () ->
            service.decide(10L, 500L, request(
                "secret-rule", "passed", Map.of("details", Map.of("authorizationToken", "secret"))
            ))
        );

        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int index = 0; index < 20; index++) {
            Map<String, Object> nested = new LinkedHashMap<>();
            cursor.put("level" + index, nested);
            cursor = nested;
        }
        assertThrows(ServiceException.class, () ->
            service.decide(10L, 500L, request("deep-rule", "passed", deep))
        );
        verify(runMapper, never()).lockTask(any());
        verify(artifactMapper, never()).insertAcceptance(any());
    }

    private AcceptanceDecisionRequest request(String key, String result, Map<String, Object> rules) {
        return new AcceptanceDecisionRequest(key, List.of(700L), result, "reviewed", rules);
    }

    private AcceptanceTaskRow task(
        String taskStatus,
        String runStatus,
        String acceptanceMode,
        Long latestRunId
    ) {
        AcceptanceTaskRow row = new AcceptanceTaskRow();
        row.setTaskId(10L);
        row.setLatestRunId(latestRunId);
        row.setTaskStatus(taskStatus);
        row.setRunStatus(runStatus);
        row.setAcceptanceMode(acceptanceMode);
        row.setAcceptanceSnapshotJson("{\"requiredArtifactTypes\":[\"document\"]}");
        return row;
    }

    private AgentArtifact artifact(Long id, String type) {
        AgentArtifact value = new AgentArtifact();
        value.setId(id);
        value.setTaskId(10L);
        value.setRunId(500L);
        value.setArtifactType(type);
        value.setStatus("available");
        return value;
    }
}
