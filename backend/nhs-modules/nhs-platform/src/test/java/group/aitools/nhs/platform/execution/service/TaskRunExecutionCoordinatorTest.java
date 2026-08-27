package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class TaskRunExecutionCoordinatorTest {

    @Test
    void persistsStateForEveryRuntimeEventAndFinalizesUnexpectedCompletion() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        AgentRuntimeExecutionService runtime = mock(AgentRuntimeExecutionService.class);
        TaskRunStateService stateService = mock(TaskRunStateService.class);
        AgentRunRequest request = request();
        ExecutionEventView event = event("text_delta");
        RuntimeEvent source = sourceEvent();
        when(provider.getIfAvailable()).thenReturn(runtime);
        when(runtime.runInternal(request)).thenReturn(Flux.just(new PersistedRuntimeEvent(source, event)));
        TaskRunExecutionCoordinator coordinator = new TaskRunExecutionCoordinator(
            provider, stateService, Runnable::run, "worker-test"
        );

        coordinator.launch(request);

        verify(stateService).onEvent(request, "worker-test", event, source);
        verify(stateService).onUnexpectedCompletion(request, "worker-test");
        assertFalse(coordinator.isLocallyActive(400L));
    }

    @Test
    void duplicateLocalLaunchIsNotScheduledTwice() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        AgentRuntimeExecutionService runtime = mock(AgentRuntimeExecutionService.class);
        TaskRunStateService stateService = mock(TaskRunStateService.class);
        List<Runnable> scheduled = new ArrayList<>();
        Executor capturingExecutor = scheduled::add;
        AgentRunRequest request = request();
        when(provider.getIfAvailable()).thenReturn(runtime);
        when(runtime.runInternal(request)).thenReturn(Flux.empty());
        TaskRunExecutionCoordinator coordinator = new TaskRunExecutionCoordinator(
            provider, stateService, capturingExecutor, "worker-test"
        );

        coordinator.launch(request);
        coordinator.launch(request);

        assertEquals(1, scheduled.size());
        assertTrue(coordinator.isLocallyActive(400L));
        scheduled.getFirst().run();
        assertFalse(coordinator.isLocallyActive(400L));
    }

    @Test
    void unavailableRuntimeFailsClosedWithoutScheduling() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        TaskRunStateService stateService = mock(TaskRunStateService.class);
        when(provider.getIfAvailable()).thenReturn(null);
        TaskRunExecutionCoordinator coordinator = new TaskRunExecutionCoordinator(
            provider, stateService, Runnable::run, "worker-test"
        );

        assertFalse(coordinator.available());
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> coordinator.launch(request())
        );
        verify(stateService, never()).onUnexpectedCompletion(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void schedulingFailureIsProjectedInsteadOfLeavingClaimedRunRunning() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        AgentRuntimeExecutionService runtime = mock(AgentRuntimeExecutionService.class);
        TaskRunStateService stateService = mock(TaskRunStateService.class);
        when(provider.getIfAvailable()).thenReturn(runtime);
        Executor rejectingExecutor = command -> {
            throw new java.util.concurrent.RejectedExecutionException("executor stopped");
        };
        TaskRunExecutionCoordinator coordinator = new TaskRunExecutionCoordinator(
            provider, stateService, rejectingExecutor, "worker-test"
        );

        coordinator.launchOrMarkFailed(request());

        verify(stateService).onLaunchFailure(
            org.mockito.ArgumentMatchers.eq(400L),
            org.mockito.ArgumentMatchers.eq(30L),
            org.mockito.ArgumentMatchers.eq(500L),
            org.mockito.ArgumentMatchers.eq("worker-test"),
            org.mockito.ArgumentMatchers.any(java.util.concurrent.RejectedExecutionException.class)
        );
        assertFalse(coordinator.isLocallyActive(400L));
    }

    private AgentRunRequest request() {
        return new AgentRunRequest(
            new RuntimeExecutionKey("task-run-400", "a".repeat(64)),
            101L,
            20L,
            30L,
            400L,
            500L,
            600L,
            "agent",
            "session",
            "input",
            "prompt",
            new RuntimeModelConfig(
                "openai-compatible", "model", "https://model.example/v1", "env:MODEL_KEY", Map.of()
            ),
            "run-400",
            4,
            Map.of("workspaceAccess", "none"),
            Map.of("taskVersionId", 700L)
        );
    }

    private ExecutionEventView event(String type) {
        return new ExecutionEventView(
            "event", "a".repeat(64), 20L, 400L, 500L, 1L,
            type, "success", "summary", Map.of(), "public", LocalDateTime.now()
        );
    }

    private RuntimeEvent sourceEvent() {
        return new RuntimeEvent(
            "event",
            new RuntimeExecutionKey("task-run-400", "a".repeat(64)),
            20L,
            400L,
            500L,
            RuntimeEventType.TEXT_DELTA,
            RuntimeEventStatus.SUCCESS,
            Instant.now(),
            "summary",
            Map.of(),
            RuntimeSensitiveLevel.PUBLIC
        );
    }
}
