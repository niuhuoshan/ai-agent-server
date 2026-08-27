package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.conversation.web.ConversationCancellationResult;
import group.aitools.nhs.platform.conversation.web.ConversationTurnView;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.execution.web.TaskRunActionResult;
import group.aitools.nhs.platform.execution.web.TaskRunView;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.service.ChatCodeExecutionService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * 负责会话Cancellation相关的业务编排与领域规则处理。
 *
 * Coordinates the Nhs global stop contract across all durable execution
 * lanes rooted in one private conversation.
 */
@Service
public class ConversationCancellationService {

    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of(
        "succeeded", "failed", "cancelled", "expired"
    );

    private final ConversationTurnApplicationService turnService;
    private final TaskRunApplicationService taskRunService;
    private final ChatCodeExecutionService chatCodeService;
    private final SandboxRunnerMapper sandboxMapper;

    /**
     * 创建 {@code ConversationCancellationService} 实例并初始化所需依赖。
     *
     * @param turnService 会话回合Service参数
     * @param taskRunService 任务RunService参数
     * @param chatCodeService 对话CodeService参数
     * @param sandboxMapper 沙箱Mapper参数
     */
    @Autowired
    public ConversationCancellationService(
        ConversationTurnApplicationService turnService,
        TaskRunApplicationService taskRunService,
        ChatCodeExecutionService chatCodeService,
        SandboxRunnerMapper sandboxMapper
    ) {
        this.turnService = turnService;
        this.taskRunService = taskRunService;
        this.chatCodeService = chatCodeService;
        this.sandboxMapper = sandboxMapper;
    }

    /**
 * 判断{@code cel}是否满足要求。
 *
     * Requests cancellation in the following order: validate the owned
     * conversation/trace, persist the turn stop fact, cancel task runs, then
     * consume sandbox leases. Every operation is owner-bound and repeatable.
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationCancellationResult cancel(
        Long conversationId,
        String requestedTraceId,
        String reason
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (conversationId == null || conversationId <= 0) {
            throw new ServiceException("会话ID无效", HttpStatus.BAD_REQUEST);
        }
        ConversationTurnView active = turnService.active(conversationId);
        String traceId = requestedTraceId == null || requestedTraceId.isBlank()
            ? active == null ? null : active.traceId() : requestedTraceId.strip();
        if (active != null && traceId != null && !traceId.equals(active.traceId())) {
            throw new ServiceException("Trace ID与当前会话回合不匹配", HttpStatus.CONFLICT);
        }
        String normalizedReason = normalizeReason(reason);

        ConversationTurnApplicationService.StopOutcome turnOutcome = null;
        if (active != null) {
            turnOutcome = turnService.stopWithOutcome(
                conversationId, active.id(), normalizedReason
            );
        }

        List<TaskRunActionResult> taskResults = taskRunService == null
            ? List.of() : java.util.Objects.requireNonNullElse(
                taskRunService.cancelActiveForConversation(conversationId, normalizedReason), List.of()
            );
        int taskRunsCancelled = (int) taskResults.stream()
            .map(TaskRunActionResult::run)
            .filter(java.util.Objects::nonNull)
            .filter(run -> "cancelled".equals(run.status()))
            .count();
        List<Long> taskRunIds = taskResults.stream()
            .map(TaskRunActionResult::run)
            .filter(java.util.Objects::nonNull)
            .map(TaskRunView::id)
            .filter(java.util.Objects::nonNull)
            .toList();

        int canvasStopped = chatCodeService == null
            ? 0 : chatCodeService.cancelAllForConversation(conversationId, normalizedReason);
        if (!taskRunIds.isEmpty() && sandboxMapper != null) {
            canvasStopped += sandboxMapper.cancelTaskRunJobs(
                taskRunIds, normalizedReason, LocalDateTime.now(ZoneOffset.UTC)
            );
        }

        boolean runCancelled = turnOutcome != null
            && turnOutcome.turn() != null
            && "cancelled".equals(turnOutcome.turn().status());
        int sessionLocksReleased = turnOutcome != null && turnOutcome.runtimeInterrupted() ? 1 : 0;
        // A task run cancellation consumes one durable execution lane.  This
        // count is intentionally derived from observed terminal transitions;
        // it is never a fixed compatibility placeholder.
        sessionLocksReleased += (int) taskResults.stream()
            .filter(result -> !result.replayed())
            .map(TaskRunActionResult::run)
            .filter(java.util.Objects::nonNull)
            .filter(run -> "cancelled".equals(run.status()))
            .count();

        boolean turnTerminal = active == null || turnOutcome == null
            || isTerminal(turnOutcome.turn() == null ? null : turnOutcome.turn().status());
        boolean taskTerminal = taskResults.stream()
            .allMatch(result -> result.run() == null || isTerminal(result.run().status()));
        boolean anyAction = active != null || !taskResults.isEmpty() || canvasStopped > 0;
        String status = turnOutcome == null || turnOutcome.turn() == null
            ? active == null ? "idle" : active.status() : turnOutcome.turn().status();
        String outcomeReason = anyAction
            ? turnTerminal && taskTerminal ? "cancel_requested" : "stop_requested"
            : "no_active_turn";
        return new ConversationCancellationResult(
            conversationId, traceId, anyAction, turnTerminal && taskTerminal,
            sessionLocksReleased, runCancelled, canvasStopped, taskRunsCancelled,
            status, outcomeReason,
            turnOutcome == null || turnOutcome.turn() == null ? null : turnOutcome.turn().id()
        );
    }

    /**
     * 判断{@code Terminal}是否满足要求。
     *
     * @param status 目标状态
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isTerminal(String status) {
        return TERMINAL_RUN_STATUSES.contains(status);
    }

    /**
     * 处理{@code normalizeReason}并返回对应结果。
     *
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    private String normalizeReason(String reason) {
        String value = reason == null || reason.isBlank() ? "用户停止会话回复" : reason.strip();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
