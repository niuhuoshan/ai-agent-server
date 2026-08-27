package group.aitools.nhs.platform.sandbox;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentRunRuntimeMapper;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.persistence.row.AgentRunRuntimeRow;
import group.aitools.nhs.platform.execution.service.TaskRunExecutionCoordinator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.sandbox.service.ExternalExecutionResumeService;
import group.aitools.nhs.platform.sandbox.mapper.ExternalExecutionResumeMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.ExternalExecutionResumeRow;
import group.aitools.nhs.platform.workflow.service.WorkflowRunCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ExternalExecutionResumeServiceTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private CurrentPrincipalProvider principalProvider;
    private AgentExecutionEventMapper eventMapper;
    private AgentRunRuntimeMapper runtimeMapper;
    private TaskRunCommandMapper runMapper;
    private TaskRunExecutionCoordinator coordinator;
    private WorkflowRunCoordinator workflowCoordinator;
    private ExternalExecutionResumeMapper resumeMapper;
    private PlatformIdGenerator idGenerator;
    private ExternalExecutionResumeService service;
    private AgentRunRequest frozen;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        eventMapper = mock(AgentExecutionEventMapper.class);
        runtimeMapper = mock(AgentRunRuntimeMapper.class);
        runMapper = mock(TaskRunCommandMapper.class);
        coordinator = mock(TaskRunExecutionCoordinator.class);
        workflowCoordinator = mock(WorkflowRunCoordinator.class);
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        when(coordinator.workerId()).thenReturn("worker-a");
        frozen = new AgentRunRequest(
            new RuntimeExecutionKey("run-11", "trace-11"),
            101L, null, 501L, 11L, 12L, 901L, "agent", "session", "input", "system",
            new RuntimeModelConfig("openai", "model", null, "env:MODEL_KEY", Map.of()),
            "run-11", 10, Map.of(), Map.of()
        );
        AgentExecutionEvent event = new AgentExecutionEvent();
        event.setRunId(11L);
        event.setStepId(12L);
        event.setTraceId("trace-11");
        event.setPayloadJson(jsonMapper.writeValueAsString(Map.of(
            "replyId", "reply-11",
            "toolCalls", List.of(Map.of("id", "call-1", "name", "platform_tool_9"))
        )));
        when(eventMapper.selectExternalExecutionEvent("reply-11", 101L)).thenReturn(event);
        AgentRunRuntimeRow runtime = new AgentRunRuntimeRow();
        runtime.setId(11L);
        runtime.setTaskId(501L);
        runtime.setTraceId("trace-11");
        runtime.setRuntimeSnapshotJson(jsonMapper.writeValueAsString(frozen));
        when(runtimeMapper.selectRuntimeSnapshotByRunAndStep(11L, 12L)).thenReturn(runtime);
        when(runMapper.claimResumedRun(501L, 11L, "worker-a")).thenReturn(1);
        when(runMapper.startStep(11L, 12L)).thenReturn(1);
        service = new ExternalExecutionResumeService(
            principalProvider, eventMapper, runtimeMapper, runMapper, coordinator,
            workflowCoordinator, jsonMapper
        );
    }

    @Test
    void resumesOnlyTheServerOwnedToolCallSnapshot() {
        var result = service.resume("reply-11", List.of(Map.of(
            "id", "call-1", "name", "platform_tool_9", "output", "ok", "state", "success"
        )));

        assertEquals(501L, result.taskId());
        assertEquals(11L, result.runId());
        verify(coordinator).launchResumeOrMarkFailed(any());
    }

    @Test
    void rejectsForgedToolIdentityBeforeClaimingTheRun() {
        assertThrows(RuntimeException.class, () -> service.resume("reply-11", List.of(Map.of(
            "id", "other-call", "name", "platform_tool_9", "output", "ok"
        ))));

        verify(runMapper, org.mockito.Mockito.never())
            .claimResumedRun(eq(501L), eq(11L), eq("worker-a"));
    }

    @Test
    void returnsReplayForSameResultAndRejectsDifferentResult() {
        resumeMapper = mock(ExternalExecutionResumeMapper.class);
        idGenerator = mock(PlatformIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(901L);
        ExternalExecutionResumeRow[] stored = new ExternalExecutionResumeRow[1];
        when(resumeMapper.selectForUpdate(101L, "reply-11"))
            .thenAnswer(ignored -> stored[0]);
        when(resumeMapper.insertPending(any())).thenAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            return 1;
        });
        when(resumeMapper.markDispatched(eq(101L), eq("reply-11"), any())).thenAnswer(invocation -> {
            stored[0].setStatus("dispatched");
            return 1;
        });
        ExternalExecutionResumeService idempotent = new ExternalExecutionResumeService(
            principalProvider, eventMapper, runtimeMapper, runMapper, coordinator,
            workflowCoordinator, jsonMapper, resumeMapper, idGenerator
        );

        var first = idempotent.resume("reply-11", List.of(Map.of(
            "id", "call-1", "name", "platform_tool_9", "output", "ok", "state", "success"
        )));
        assertEquals(false, first.replayed());

        var replay = idempotent.resume("reply-11", List.of(Map.of(
            "id", "call-1", "name", "platform_tool_9", "output", "ok", "state", "success"
        )));
        assertEquals(true, replay.replayed());

        assertThrows(RuntimeException.class, () -> idempotent.resume("reply-11", List.of(Map.of(
            "id", "call-1", "name", "platform_tool_9", "output", "tampered", "state", "success"
        ))));
        verify(runMapper, org.mockito.Mockito.times(1))
            .claimResumedRun(eq(501L), eq(11L), eq("worker-a"));
    }

}
