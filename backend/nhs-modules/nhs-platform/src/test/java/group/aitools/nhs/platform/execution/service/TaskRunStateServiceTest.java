package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import group.aitools.nhs.platform.approval.service.ApprovalRequestRecorder;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class TaskRunStateServiceTest {

    @Test
    void approvalEventReleasesLeaseAndLeavesStepWaiting() {
        TaskRunCommandMapper mapper = mock(TaskRunCommandMapper.class);
        ApprovalRequestRecorder recorder = mock(ApprovalRequestRecorder.class);
        when(mapper.markWaiting(400L, "worker", "waiting_approval", "tool_approval"))
            .thenReturn(1);
        TaskRunStateService service = new TaskRunStateService(
            mapper, recorder, mock(NotificationApplicationService.class)
        );

        ExecutionEventView event = event("approval_required", "pending");
        RuntimeEvent source = approvalEvent();
        service.onEvent(request(), "worker", event, source);

        verify(recorder).record(101L, 30L, 400L, 500L, source, event);
        verify(mapper).markWaiting(400L, "worker", "waiting_approval", "tool_approval");
        verify(mapper).markStepWaiting(400L, 500L);
    }

    @Test
    void successfulRunMovesTaskToVerificationNotCompletion() {
        TaskRunCommandMapper mapper = mock(TaskRunCommandMapper.class);
        ApprovalRequestRecorder recorder = mock(ApprovalRequestRecorder.class);
        when(mapper.markSucceeded(400L, "worker")).thenReturn(1);
        TaskRunStateService service = new TaskRunStateService(
            mapper, recorder, mock(NotificationApplicationService.class)
        );

        service.onEvent(request(), "worker", event("run_finished", "success"));

        verify(mapper).markStepSucceeded(400L, 500L);
        verify(mapper).markTaskVerifying(30L, 400L);
    }

    @Test
    void delayedCancelledEventCannotConvertPausedRunToCancelled() {
        TaskRunCommandMapper mapper = mock(TaskRunCommandMapper.class);
        ApprovalRequestRecorder recorder = mock(ApprovalRequestRecorder.class);
        when(mapper.markRuntimeCancelled(400L, "worker", "pause interrupt")).thenReturn(0);
        TaskRunStateService service = new TaskRunStateService(
            mapper, recorder, mock(NotificationApplicationService.class)
        );

        service.onEvent(request(), "worker", event("cancelled", "success", "pause interrupt"));

        verify(mapper, never()).cancelSteps(400L);
        verify(mapper, never()).markTaskCancelled(30L, 400L, 101L);
    }

    @Test
    void launchFailureTerminatesRunStepAndBlocksTask() {
        TaskRunCommandMapper mapper = mock(TaskRunCommandMapper.class);
        ApprovalRequestRecorder recorder = mock(ApprovalRequestRecorder.class);
        when(mapper.failRun(400L, "RUNTIME_LAUNCH_FAILED", "executor stopped")).thenReturn(1);
        TaskRunStateService service = new TaskRunStateService(
            mapper, recorder, mock(NotificationApplicationService.class)
        );

        service.onLaunchFailure(
            400L, 30L, 500L, "worker", new IllegalStateException("executor stopped")
        );

        verify(mapper).failStep(400L, 500L, "RUNTIME_LAUNCH_FAILED", "executor stopped");
        verify(mapper).markTaskBlocked(30L, 400L);
    }

    private AgentRunRequest request() {
        return new AgentRunRequest(
            new RuntimeExecutionKey("task-run-400", "a".repeat(64)),
            101L,
            null,
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
            Map.of(),
            Map.of("taskVersionId", 700L)
        );
    }

    private ExecutionEventView event(String type, String status) {
        return event(type, status, "summary");
    }

    private ExecutionEventView event(String type, String status, String summary) {
        return new ExecutionEventView(
            "event", "a".repeat(64), null, 400L, 500L, 1L,
            type, status, summary, Map.of(), "internal", LocalDateTime.now()
        );
    }

    private RuntimeEvent approvalEvent() {
        return new RuntimeEvent(
            "source-approval",
            new RuntimeExecutionKey("task-run-400", "a".repeat(64)),
            null,
            400L,
            500L,
            RuntimeEventType.APPROVAL_REQUIRED,
            RuntimeEventStatus.PENDING,
            Instant.now(),
            "approval required",
            Map.of(
                "replyId", "reply-1",
                "toolCalls", java.util.List.of(Map.of(
                    "id", "tool-call-1",
                    "name", "write_file",
                    "input", Map.of("path", "report.txt")
                ))
            ),
            RuntimeSensitiveLevel.INTERNAL
        );
    }
}
