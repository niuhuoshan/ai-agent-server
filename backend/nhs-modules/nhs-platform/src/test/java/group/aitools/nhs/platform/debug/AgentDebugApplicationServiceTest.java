package group.aitools.nhs.platform.debug;

import group.aitools.nhs.platform.agent.domain.AgentDefinition;
import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionMapper;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionVersionMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.debug.mapper.AgentDebugRunMapper;
import group.aitools.nhs.platform.debug.persistence.row.AgentDebugRunRow;
import group.aitools.nhs.platform.debug.service.AgentDebugApplicationService;
import group.aitools.nhs.platform.debug.service.AgentDebugAuditService;
import group.aitools.nhs.platform.debug.web.AgentDebugRunDetailView;
import group.aitools.nhs.platform.debug.web.CreateAgentDebugRunRequest;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.execution.web.TaskRunActionResult;
import group.aitools.nhs.platform.execution.web.TaskRunView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.AuthorizationService;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.web.CreateTaskRequest;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import group.aitools.nhs.platform.task.web.TaskView;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AgentDebugApplicationServiceTest {

    private final CurrentPrincipal principal = new CurrentPrincipal(
        101L, "debugger", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private CurrentPrincipalProvider principals;
    private AuthorizationService authorization;
    private AuthorizationEnforcer enforcer;
    private PlatformIdGenerator ids;
    private AgentDefinitionMapper definitions;
    private AgentDefinitionVersionMapper versions;
    private AgentDebugRunMapper debugRuns;
    private AgentExecutionEventMapper events;
    private TaskApplicationService tasks;
    private TaskRunApplicationService runs;
    private AgentDebugAuditService audits;
    private AgentDebugApplicationService service;
    private AgentDefinition definition;
    private AgentDefinitionVersion version;
    private TaskRunView running;

    @BeforeEach
    void setUp() {
        principals = mock(CurrentPrincipalProvider.class);
        authorization = mock(AuthorizationService.class);
        enforcer = mock(AuthorizationEnforcer.class);
        ids = mock(PlatformIdGenerator.class);
        definitions = mock(AgentDefinitionMapper.class);
        versions = mock(AgentDefinitionVersionMapper.class);
        debugRuns = mock(AgentDebugRunMapper.class);
        events = mock(AgentExecutionEventMapper.class);
        tasks = mock(TaskApplicationService.class);
        runs = mock(TaskRunApplicationService.class);
        audits = mock(AgentDebugAuditService.class);
        when(principals.currentPrincipal()).thenReturn(principal);
        service = new AgentDebugApplicationService(
            principals, authorization, enforcer, ids, definitions, versions,
            debugRuns, events, tasks, runs, audits, JsonMapper.builder().build()
        );

        definition = new AgentDefinition();
        definition.setId(10L);
        definition.setAgentKey("orders");
        definition.setName("订单助手");
        definition.setStatus("active");
        definition.setIsDefault(true);
        definition.setPublishedVersionId(11L);
        version = new AgentDefinitionVersion();
        version.setId(11L);
        version.setAgentId(10L);
        version.setVersionNo(3);
        version.setModelId(12L);
        version.setStatus("published");
        version.setContentHash("content-hash");
        version.setPublishedAt(LocalDateTime.now().minusDays(1));
        when(definitions.selectDefinitionById(10L)).thenReturn(definition);
        when(versions.selectVersion(10L, 11L)).thenReturn(version);

        LocalDateTime now = LocalDateTime.now();
        running = new TaskRunView(
            501L, 401L, 301L, "trace-501", "running", 1, null,
            now.minusSeconds(1), null, null, null, null, null, 101L, now.minusSeconds(1)
        );
        when(runs.get(401L, 501L)).thenReturn(running);
        when(runs.steps(401L, 501L)).thenReturn(List.of());
        when(events.selectTaskRunEvents(401L, 501L, 0L, 500)).thenReturn(List.of());
    }

    @Test
    void createsRestrictedDebugTaskThenStartsTheGovernedRuntime() {
        TaskView task = mock(TaskView.class);
        when(task.id()).thenReturn(401L);
        when(tasks.create(any())).thenReturn(new TaskMutationResult(task, 301L, false));
        when(runs.create(eq(401L), any())).thenReturn(new TaskRunActionResult(running, false));
        when(runs.start(401L, 501L)).thenReturn(new TaskRunActionResult(running, false));
        when(ids.nextId()).thenReturn(601L);
        AtomicReference<AgentDebugRunRow> inserted = new AtomicReference<>();
        when(debugRuns.insert(any())).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(debugRuns.selectOwned(601L, 101L)).thenAnswer(ignored -> inserted.get());

        AgentDebugRunDetailView result = service.create(
            new CreateAgentDebugRunRequest("case-1", 10L, 11L, "检查本月订单")
        );

        ArgumentCaptor<CreateTaskRequest> taskRequest = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(tasks).create(taskRequest.capture());
        assertEquals("restricted", taskRequest.getValue().visibility());
        assertEquals("L0_chat", taskRequest.getValue().lifecycleLevel());
        assertEquals(11L, taskRequest.getValue().agentVersionId());
        assertEquals("agent_debug", taskRequest.getValue().externalRefs().get("source"));
        verify(runs).create(eq(401L), any());
        verify(runs).start(401L, 501L);
        verify(enforcer).requireAllowed(eq(principal), any());
        assertEquals(601L, result.summary().id());
        assertEquals("检查本月订单", result.summary().input());
    }

    @Test
    void aggregatesActualModelUsageToolCallsAndPersistedTextOutput() {
        AgentDebugRunRow row = row(601L);
        when(debugRuns.selectOwned(601L, 101L)).thenReturn(row);
        LocalDateTime now = LocalDateTime.now();
        TaskRunView succeeded = new TaskRunView(
            501L, 401L, 301L, "trace-501", "succeeded", 1, null,
            now.minusSeconds(2), now, null, null, null, null, 101L, now.minusSeconds(2)
        );
        when(runs.get(401L, 501L)).thenReturn(succeeded);
        when(events.selectTaskRunEvents(401L, 501L, 0L, 500)).thenReturn(List.of(
            event(1L, "model_call_finished", "", "internal",
                "{\"promptTokens\":12,\"completionTokens\":8,\"cachedTokens\":2,"
                    + "\"totalTokens\":20,\"durationMs\":450}"),
            event(2L, "tool_call_started", "Tool call started", "public", "{\"toolName\":\"lookup\"}"),
            event(3L, "text_delta", "你好", "public", "{}"),
            event(4L, "run_finished", "done", "public", "{}")
        ));

        AgentDebugRunDetailView detail = service.get(601L);

        assertEquals(20L, detail.metrics().totalTokens());
        assertEquals(1, detail.metrics().modelCalls());
        assertEquals(1, detail.metrics().toolCalls());
        assertTrue(detail.metrics().elapsedMs() >= 1900L);
        assertEquals("你好", detail.finalOutput());
    }

    @Test
    void keepsNonOwnedDebugRunsPrivateAndAuditsTheDeniedLookup() {
        when(debugRuns.selectOwned(999L, 101L)).thenReturn(null);

        ServiceException failure = assertThrows(ServiceException.class, () -> service.get(999L));

        assertEquals(404, failure.getCode());
        verify(runs, never()).get(any(), any());
        verify(audits).record(
            eq(principal), eq("debug_view"), eq(999L), eq(null), eq("deny"), any(), any()
        );
    }

    @Test
    void stopPausesRunningAttemptSoTheSameCheckpointCanResume() {
        AgentDebugRunRow row = row(601L);
        when(debugRuns.selectOwned(601L, 101L)).thenReturn(row);
        when(runs.pause(401L, 501L, "人工停止"))
            .thenReturn(new TaskRunActionResult(running, false));

        service.stop(601L, "人工停止");

        verify(runs).pause(401L, 501L, "人工停止");
        verify(runs, never()).cancel(any(), any(), any());
    }

    private AgentDebugRunRow row(Long id) {
        AgentDebugRunRow row = new AgentDebugRunRow();
        row.setId(id);
        row.setOwnerId(101L);
        row.setAgentId(10L);
        row.setAgentVersionId(11L);
        row.setTaskId(401L);
        row.setRunId(501L);
        row.setInputText("检查本月订单");
        row.setInputSha256("input-hash");
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private AgentExecutionEvent event(
        long cursor,
        String type,
        String summary,
        String sensitiveLevel,
        String projection
    ) {
        AgentExecutionEvent event = new AgentExecutionEvent();
        event.setId(700L + cursor);
        event.setEventId("event-" + cursor);
        event.setTraceId("trace-501");
        event.setRunId(501L);
        event.setStepId(601L);
        event.setCursor(cursor);
        event.setEventType(type);
        event.setEventStatus("success");
        event.setSummary(summary);
        event.setPayloadJson("{}");
        event.setQueryProjectionJson(projection);
        event.setSensitiveLevel(sensitiveLevel);
        event.setOccurredAt(LocalDateTime.now());
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }
}
