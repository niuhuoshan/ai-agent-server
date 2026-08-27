package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.conversation.web.ConversationTurnView;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.execution.web.TaskRunActionResult;
import group.aitools.nhs.platform.execution.web.TaskRunView;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.service.ChatCodeExecutionService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConversationCancellationServiceTest {

    private ConversationTurnApplicationService turns;
    private TaskRunApplicationService taskRuns;
    private ChatCodeExecutionService chatCode;
    private SandboxRunnerMapper sandbox;
    private ConversationCancellationService service;

    @BeforeEach
    void setUp() {
        turns = mock(ConversationTurnApplicationService.class);
        taskRuns = mock(TaskRunApplicationService.class);
        chatCode = mock(ChatCodeExecutionService.class);
        sandbox = mock(SandboxRunnerMapper.class);
        service = new ConversationCancellationService(turns, taskRuns, chatCode, sandbox);
        when(taskRuns.cancelActiveForConversation(any(), any())).thenReturn(List.of());
    }

    @Test
    void cancelsTurnTaskRunsAndSandboxLeasesWithObservedCounts() {
        ConversationTurnView active = turn(17L, "trace-17", "running");
        ConversationTurnView cancelled = turn(17L, "trace-17", "cancelled");
        when(turns.active(7L)).thenReturn(active);
        when(turns.stopWithOutcome(7L, 17L, "stop now")).thenReturn(
            new ConversationTurnApplicationService.StopOutcome(cancelled, true)
        );
        TaskRunActionResult task = new TaskRunActionResult(taskRun(31L, 41L), false);
        when(taskRuns.cancelActiveForConversation(7L, "stop now")).thenReturn(List.of(task));
        when(chatCode.cancelAllForConversation(7L, "stop now")).thenReturn(2);
        when(sandbox.cancelTaskRunJobs(eq(List.of(41L)), eq("stop now"), any())).thenReturn(1);

        var result = service.cancel(7L, "trace-17", "stop now");

        assertThat(result.success()).isTrue();
        assertThat(result.laneReleased()).isTrue();
        assertThat(result.runCancelled()).isTrue();
        assertThat(result.sessionLocksReleased()).isEqualTo(2);
        assertThat(result.canvasStopped()).isEqualTo(3);
        assertThat(result.taskRunsCancelled()).isEqualTo(1);
        assertThat(result.reason()).isEqualTo("cancel_requested");
        assertThat(result.turnId()).isEqualTo(17L);
    }

    @Test
    void rejectsTraceConflictBeforeAnyCancellationSideEffect() {
        when(turns.active(7L)).thenReturn(turn(17L, "trace-current", "running"));

        assertThatThrownBy(() -> service.cancel(7L, "trace-stale", null))
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getCode())
            .isEqualTo(HttpStatus.CONFLICT);

        verify(turns, never()).stopWithOutcome(any(), any(), any());
        verifyNoInteractions(taskRuns, chatCode, sandbox);
    }

    @Test
    void idleRepeatIsExplicitAndStillChecksForConversationSandboxWork() {
        when(turns.active(7L)).thenReturn(null);
        when(chatCode.cancelAllForConversation(7L, "用户停止会话回复")).thenReturn(0);

        var result = service.cancel(7L, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.laneReleased()).isTrue();
        assertThat(result.reason()).isEqualTo("no_active_turn");
        assertThat(result.status()).isEqualTo("idle");
        verify(taskRuns).cancelActiveForConversation(7L, "用户停止会话回复");
        verify(chatCode).cancelAllForConversation(7L, "用户停止会话回复");
    }

    @Test
    void ownerLookupFailurePreventsDownstreamCancellation() {
        ServiceException missing = new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        when(turns.active(7L)).thenThrow(missing);

        assertThatThrownBy(() -> service.cancel(7L, null, null)).isSameAs(missing);

        verifyNoInteractions(taskRuns, chatCode, sandbox);
    }

    private ConversationTurnView turn(Long id, String traceId, String status) {
        return new ConversationTurnView(
            id, 7L, traceId, 11L, 12L, status, false,
            LocalDateTime.now(), "cancelled".equals(status) ? LocalDateTime.now() : null
        );
    }

    private TaskRunView taskRun(Long taskId, Long runId) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskRunView(
            runId, taskId, 51L, null, "task-trace", "cancelled", 1,
            null, now, now, null, null, null, "stop now", 101L, now
        );
    }
}
