package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.platform.approval.service.ApprovalRequestRecorder;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import group.aitools.nhs.platform.sandbox.service.SandboxExternalExecutionDispatcher;
import group.aitools.nhs.platform.workflow.service.WorkflowRunCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 负责任务RunState相关的业务编排与领域规则处理。
 * Applies runtime events to run, step and task state in one database transaction. */
@Service
public class TaskRunStateService {

    private final TaskRunCommandMapper runMapper;
    private final ApprovalRequestRecorder approvalRecorder;
    private final NotificationApplicationService notificationService;
    private final SandboxExternalExecutionDispatcher sandboxDispatcher;
    private final ObjectProvider<WorkflowRunCoordinator> workflowCoordinatorProvider;

    @Autowired
    public TaskRunStateService(
        TaskRunCommandMapper runMapper,
        ApprovalRequestRecorder approvalRecorder,
        NotificationApplicationService notificationService,
        SandboxExternalExecutionDispatcher sandboxDispatcher,
        ObjectProvider<WorkflowRunCoordinator> workflowCoordinatorProvider
    ) {
        this.runMapper = runMapper;
        this.approvalRecorder = approvalRecorder;
        this.notificationService = notificationService;
        this.sandboxDispatcher = sandboxDispatcher;
        this.workflowCoordinatorProvider = workflowCoordinatorProvider;
    }

    /**
     * 创建 {@code TaskRunStateService} 实例并初始化所需依赖。
     *
     * @param runMapper {@code runMapper}参数
     * @param approvalRecorder 审批Recorder参数
     * @param notificationService 通知Service参数
     */
    public TaskRunStateService(
        TaskRunCommandMapper runMapper,
        ApprovalRequestRecorder approvalRecorder,
        NotificationApplicationService notificationService
    ) {
        this(runMapper, approvalRecorder, notificationService, null, null);
    }

    /**
     * 处理on事件相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     * @param event 事件参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void onEvent(AgentRunRequest request, String workerId, ExecutionEventView event) {
        applyEvent(identity(request), workerId, event, null);
    }

    /**
     * 处理on事件相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     * @param event 事件参数
     * @param source 数据源参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void onEvent(
        AgentRunRequest request,
        String workerId,
        ExecutionEventView event,
        RuntimeEvent source
    ) {
        dispatchSandbox(request, event, source);
        WorkflowRunCoordinator workflow = workflow();
        if (workflow != null && workflow.owns(request)) {
            workflow.onEvent(request, workerId, event, source);
            return;
        }
        applyEvent(identity(request), workerId, event, source);
    }

    /**
     * 处理onResume事件相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     * @param event 事件参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void onResumeEvent(AgentResumeRequest request, String workerId, ExecutionEventView event) {
        applyEvent(identity(request), workerId, event, null);
    }

    /**
     * 处理onResume事件相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     * @param event 事件参数
     * @param source 数据源参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void onResumeEvent(
        AgentResumeRequest request,
        String workerId,
        ExecutionEventView event,
        RuntimeEvent source
    ) {
        dispatchSandbox(request, event, source);
        WorkflowRunCoordinator workflow = workflow();
        if (workflow != null && workflow.owns(request)) {
            workflow.onEvent(workflow.frozen(request), workerId, event, source);
            return;
        }
        applyEvent(identity(request), workerId, event, source);
    }

    /**
     * 执行沙箱相关的处理流程。
     *
     * @param request 请求参数
     * @param event 事件参数
     * @param source 数据源参数
     */
    private void dispatchSandbox(
        AgentRunRequest request,
        ExecutionEventView event,
        RuntimeEvent source
    ) {
        if (sandboxDispatcher != null && "external_execution_required".equals(event.eventType())) {
            sandboxDispatcher.dispatch(request, source);
        }
    }

    /**
     * 执行沙箱相关的处理流程。
     *
     * @param request 请求参数
     * @param event 事件参数
     * @param source 数据源参数
     */
    private void dispatchSandbox(
        AgentResumeRequest request,
        ExecutionEventView event,
        RuntimeEvent source
    ) {
        if (sandboxDispatcher != null && "external_execution_required".equals(event.eventType())) {
            sandboxDispatcher.dispatch(request, source);
        }
    }

    /**
     * 处理apply事件相关逻辑。
     *
     * @param run {@code run}参数
     * @param workerId 资源标识
     * @param event 事件参数
     * @param source 数据源参数
     */
    private void applyEvent(
        RunIdentity run,
        String workerId,
        ExecutionEventView event,
        RuntimeEvent source
    ) {
        String type = event.eventType();
        switch (type) {
            case "approval_required" -> {
                approvalRecorder.record(
                    run.userId(), run.taskId(), run.runId(), run.stepId(), source, event
                );
                markWaiting(run, workerId, "waiting_approval", "tool_approval");
            }
            case "external_execution_required" -> markWaiting(
                run, workerId, "waiting_input", "external_execution"
            );
            case "run_finished" -> markSucceeded(run, workerId);
            case "failed" -> markFailed(run, workerId, "RUNTIME_FAILED", event.summary());
            case "permission_denied" -> markFailed(
                run, workerId, "RUNTIME_PERMISSION_DENIED", event.summary()
            );
            case "iteration_limit_reached" -> markFailed(
                run, workerId, "RUNTIME_ITERATION_LIMIT", event.summary()
            );
            case "cancelled" -> markCancelled(run, workerId, event.summary());
            default -> runMapper.renewLease(run.runId(), workerId);
        }
    }

    /**
     * 处理{@code onFailure}相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     * @param failure {@code failure}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void onFailure(AgentRunRequest request, String workerId, Throwable failure) {
        WorkflowRunCoordinator workflow = workflow();
        if (workflow != null && workflow.owns(request)) {
            workflow.onFailure(request, "RUNTIME_STREAM_ERROR", safeSummary(failure.getMessage()));
            return;
        }
        markFailed(identity(request), workerId, "RUNTIME_STREAM_ERROR", safeSummary(failure.getMessage()));
    }

    /**
     * 处理{@code onResumeFailure}相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     * @param failure {@code failure}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void onResumeFailure(AgentResumeRequest request, String workerId, Throwable failure) {
        WorkflowRunCoordinator workflow = workflow();
        if (workflow != null && workflow.owns(request)) {
            workflow.onFailure(
                workflow.frozen(request), "RUNTIME_RESUME_ERROR", safeSummary(failure.getMessage())
            );
            return;
        }
        markFailed(identity(request), workerId, "RUNTIME_RESUME_ERROR", safeSummary(failure.getMessage()));
    }

    /**
     * 处理{@code onUnexpectedCompletion}相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void onUnexpectedCompletion(AgentRunRequest request, String workerId) {
        WorkflowRunCoordinator workflow = workflow();
        if (workflow != null && workflow.owns(request)) {
            workflow.onUnexpectedCompletion(request);
            return;
        }
        markFailed(
            identity(request),
            workerId,
            "RUNTIME_STREAM_ENDED",
            "运行事件流结束，但没有收到完成、等待或失败事件"
        );
    }

    /**
     * 处理{@code onResumeUnexpectedCompletion}相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void onResumeUnexpectedCompletion(AgentResumeRequest request, String workerId) {
        WorkflowRunCoordinator workflow = workflow();
        if (workflow != null && workflow.owns(request)) {
            workflow.onUnexpectedCompletion(workflow.frozen(request));
            return;
        }
        markFailed(
            identity(request),
            workerId,
            "RUNTIME_RESUME_ENDED",
            "恢复事件流结束，但没有收到完成、等待或失败事件"
        );
    }

    /**
     * 处理{@code onLaunchFailure}相关逻辑。
     *
     * @param runId 资源标识
     * @param taskId 资源标识
     * @param stepId 资源标识
     * @param workerId 资源标识
     * @param failure {@code failure}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void onLaunchFailure(
        Long runId,
        Long taskId,
        Long stepId,
        String workerId,
        Throwable failure
    ) {
        String summary = safeSummary(failure == null ? null : failure.getMessage());
        if (runMapper.failRun(runId, "RUNTIME_LAUNCH_FAILED", summary) == 1) {
            runMapper.failStep(runId, stepId, "RUNTIME_LAUNCH_FAILED", summary);
            runMapper.markTaskBlocked(taskId, runId);
            notifyRun(taskId, runId, "failed", "error", "任务运行启动失败");
        }
    }

    /**
     * 处理{@code onLaunchFailure}相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     * @param failure {@code failure}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void onLaunchFailure(
        AgentRunRequest request,
        String workerId,
        Throwable failure
    ) {
        WorkflowRunCoordinator workflow = workflow();
        if (workflow != null && workflow.owns(request)) {
            workflow.onFailure(
                request, "RUNTIME_LAUNCH_FAILED",
                safeSummary(failure == null ? null : failure.getMessage())
            );
            return;
        }
        onLaunchFailure(
            request.runId(), request.taskId(), request.stepId(), workerId, failure
        );
    }

    /**
     * 处理{@code markWaiting}相关逻辑。
     *
     * @param run {@code run}参数
     * @param workerId 资源标识
     * @param status 目标状态
     * @param waitReason {@code waitReason}参数
     */
    private void markWaiting(
        RunIdentity run,
        String workerId,
        String status,
        String waitReason
    ) {
        if (runMapper.markWaiting(run.runId(), workerId, status, waitReason) == 1) {
            runMapper.markStepWaiting(run.runId(), run.stepId());
        }
    }

    /**
     * 处理{@code markSucceeded}相关逻辑。
     *
     * @param run {@code run}参数
     * @param workerId 资源标识
     */
    private void markSucceeded(RunIdentity run, String workerId) {
        if (runMapper.markSucceeded(run.runId(), workerId) == 1) {
            runMapper.markStepSucceeded(run.runId(), run.stepId());
            runMapper.markTaskVerifying(run.taskId(), run.runId());
            notifyRun(run.taskId(), run.runId(), "succeeded", "success", "任务运行已完成，等待验收");
        }
    }

    /**
     * 处理{@code markFailed}相关逻辑。
     *
     * @param run {@code run}参数
     * @param workerId 资源标识
     * @param errorCode {@code errorCode}参数
     * @param errorSummary {@code errorSummary}参数
     */
    private void markFailed(
        RunIdentity run,
        String workerId,
        String errorCode,
        String errorSummary
    ) {
        String safeSummary = safeSummary(errorSummary);
        if (runMapper.markFailed(run.runId(), workerId, errorCode, safeSummary) == 1) {
            runMapper.markStepFailed(run.runId(), run.stepId(), errorCode, safeSummary);
            runMapper.markTaskBlocked(run.taskId(), run.runId());
            notifyRun(run.taskId(), run.runId(), "failed", "error", "任务运行失败，任务已转为阻塞");
        }
    }

    /**
     * 处理{@code markCancelled}相关逻辑。
     *
     * @param run {@code run}参数
     * @param workerId 资源标识
     * @param reason {@code reason}参数
     */
    private void markCancelled(RunIdentity run, String workerId, String reason) {
        String safeReason = safeSummary(reason);
        if (runMapper.markRuntimeCancelled(run.runId(), workerId, safeReason) == 1) {
            runMapper.cancelSteps(run.runId());
            runMapper.markTaskCancelled(run.taskId(), run.runId(), run.userId());
            notifyRun(run.taskId(), run.runId(), "cancelled", "warning", "任务运行已取消");
        }
    }

    /**
     * 处理{@code notifyRun}相关逻辑。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param event 事件参数
     * @param level {@code level}参数
     * @param title {@code title}参数
     */
    private void notifyRun(
        Long taskId,
        Long runId,
        String event,
        String level,
        String title
    ) {
        notificationService.publishTaskOwner(taskId, new NotificationMessage(
            "run:" + event + ":" + runId,
            "run",
            level,
            title,
            "请打开任务运行查看详情",
            "run",
            runId
        ));
    }

    /**
     * 处理身份并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private RunIdentity identity(AgentRunRequest request) {
        return new RunIdentity(request.userId(), request.taskId(), request.runId(), request.stepId());
    }

    /**
     * 处理身份并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private RunIdentity identity(AgentResumeRequest request) {
        return new RunIdentity(request.userId(), request.taskId(), request.runId(), request.stepId());
    }

    /**
     * 处理{@code safeSummary}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeSummary(String value) {
        if (value == null || value.isBlank()) {
            return "Agent运行失败";
        }
        String normalized = value.strip().replace('\0', ' ');
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    /**
     * 处理工作流并返回对应结果。
     *
     * @return 处理结果
     */
    private WorkflowRunCoordinator workflow() {
        return workflowCoordinatorProvider == null
            ? null : workflowCoordinatorProvider.getIfAvailable();
    }

    /**
     * 封装Run身份相关的不可变数据。
     */
    private record RunIdentity(Long userId, Long taskId, Long runId, Long stepId) {
    }
}
