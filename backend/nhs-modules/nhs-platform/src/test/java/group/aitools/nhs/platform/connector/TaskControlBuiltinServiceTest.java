package group.aitools.nhs.platform.connector;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.automation.service.AutomationApplicationService;
import group.aitools.nhs.platform.automation.web.AutomationFireView;
import group.aitools.nhs.platform.automation.web.AutomationTriggerView;
import group.aitools.nhs.platform.automation.web.CreateAutomationTriggerRequest;
import group.aitools.nhs.platform.connector.service.TaskControlBuiltinService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.ServiceAccountPrincipalResolver;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.web.CreateTaskRequest;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import group.aitools.nhs.platform.task.web.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class TaskControlBuiltinServiceTest {

    private static final CurrentPrincipal ACTOR = new CurrentPrincipal(
        9L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal SERVICE_ACCOUNT = new CurrentPrincipal(
        20L, "automation", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );

    private TaskApplicationService taskService;
    private AutomationApplicationService automationService;
    private ServiceAccountPrincipalResolver accountResolver;
    private TaskControlBuiltinService service;
    private AgentRunRequest runtime;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskApplicationService.class);
        automationService = mock(AutomationApplicationService.class);
        accountResolver = mock(ServiceAccountPrincipalResolver.class);
        service = new TaskControlBuiltinService(taskService, automationService, accountResolver);
        runtime = runtime();
    }

    @Test
    void createRecurringPersistsFormalTaskAndCronTriggerFromFrozenResources() {
        when(accountResolver.requireOwnedForAutomation(ACTOR, null)).thenReturn(SERVICE_ACCOUNT);
        TaskView ready = task("ready");
        TaskView scheduled = task("scheduled");
        when(taskService.createAs(eq(ACTOR), any())).thenReturn(
            new TaskMutationResult(ready, 11L, false)
        );
        when(automationService.createForTaskOperator(eq(ACTOR), any())).thenReturn(
            trigger("active")
        );
        when(taskService.updateStatusAs(ACTOR, 10L, "scheduled")).thenReturn(scheduled);
        ArgumentCaptor<CreateTaskRequest> taskRequest = ArgumentCaptor.forClass(
            CreateTaskRequest.class
        );
        ArgumentCaptor<CreateAutomationTriggerRequest> triggerRequest = ArgumentCaptor.forClass(
            CreateAutomationTriggerRequest.class
        );

        Map<String, Object> result = service.createRecurring(
            runtime, ACTOR, "Daily report", "0 8 * * *", "Build the report",
            List.of("portal", "email"), null, null
        );

        verify(taskService).createAs(eq(ACTOR), taskRequest.capture());
        verify(automationService).createForTaskOperator(eq(ACTOR), triggerRequest.capture());
        assertEquals("L3_recurring_task", taskRequest.getValue().lifecycleLevel());
        assertEquals(100L, taskRequest.getValue().agentVersionId());
        assertEquals("use", taskRequest.getValue().resources().getFirst().permission());
        assertEquals(20L, triggerRequest.getValue().serviceAccountId());
        assertEquals("cron", triggerRequest.getValue().triggerType());
        assertEquals(List.of("portal", "email"),
            triggerRequest.getValue().config().get("notification_channels"));
        assertEquals(10L, result.get("task_id"));
        assertEquals("scheduled", result.get("task_status"));
    }

    @Test
    void statusControlsAndCancellationUseDurableAutomationAndTaskServices() {
        when(automationService.changeRecurringStatusAs(ACTOR, 10L, "paused"))
            .thenReturn(trigger("paused"));
        when(automationService.changeRecurringStatusAs(ACTOR, 10L, "active"))
            .thenReturn(trigger("active"));
        when(automationService.changeRecurringStatusAs(ACTOR, 10L, "archived"))
            .thenReturn(trigger("archived"));
        when(taskService.cancelRecurringAs(ACTOR, 10L)).thenReturn(task("cancelled"));

        assertEquals("paused", service.pause(ACTOR, 10L).get("status"));
        assertEquals("active", service.start(ACTOR, 10L).get("status"));
        assertEquals(true, service.cancel(ACTOR, 10L).get("cancelled"));

        verify(taskService).cancelRecurringAs(ACTOR, 10L);
    }

    @Test
    void manualRunUsesExecutionStableIdempotencyAndReturnsQueuedJob() {
        when(automationService.manualRunRecurringAs(eq(ACTOR), eq(10L), any())).thenReturn(
            new AutomationFireView(
                31L, 30L, "manual", "queued", 32L, null, 0, null,
                null, LocalDateTime.now(), null, false
            )
        );
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

        Map<String, Object> first = service.runManually(runtime, ACTOR, 10L);
        Map<String, Object> second = service.runManually(runtime, ACTOR, 10L);

        verify(automationService, org.mockito.Mockito.times(2))
            .manualRunRecurringAs(eq(ACTOR), eq(10L), key.capture());
        assertEquals(key.getAllValues().getFirst(), key.getAllValues().getLast());
        assertTrue(key.getValue().startsWith("builtin-manual:"));
        assertEquals(32L, first.get("job_id"));
        assertEquals(first, second);
    }

    private AgentRunRequest runtime() {
        return new AgentRunRequest(
            new RuntimeExecutionKey("execution-1", "trace-1"),
            9L, 5L, null, null, null, 100L, "agent", "session", "input", "system",
            new RuntimeModelConfig("openai", "model", null, "env:MODEL_KEY", Map.of()),
            null, 10,
            Map.of("principalId", 9L, "principalType", "human", "roles", List.of("member")),
            Map.of("taskResourceSnapshot", Map.of(
                "agentVersionId", 100L,
                "resources", List.of(Map.of(
                    "resourceType", "tool", "resourceId", 50L,
                    "permission", "invoke", "required", true
                ))
            ))
        );
    }

    private TaskView task(String status) {
        return new TaskView(
            10L, "T-10", null, "Daily report", "Build the report", null, null,
            Map.of(), "enterprise_shared", "general", "single_agent",
            "L3_recurring_task", "R1", status, 0, 0, 9L, "human", null,
            11L, null, "human", Map.of(), Map.of(), Map.of(), List.of("recurring"),
            LocalDateTime.now()
        );
    }

    private AutomationTriggerView trigger(String status) {
        return new AutomationTriggerView(
            30L, "builtin.recurring.10", "Daily report", "cron", 10L, 11L, 1L,
            20L, "0 0 8 * * *", "Asia/Shanghai", status, "fire_once", 1, 3,
            "Build the report", null,
            "active".equals(status) ? LocalDateTime.now().plusDays(1) : null,
            1L, Map.of(), LocalDateTime.now()
        );
    }
}
