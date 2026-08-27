package group.aitools.nhs.platform.workflow.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.runtime.spi.RuntimeResumeMode;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.service.TaskRunExecutionCoordinator;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import group.aitools.nhs.platform.workflow.mapper.WorkflowRunMapper;
import group.aitools.nhs.platform.workflow.persistence.row.WorkflowRunStepRow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表示工作流RunCoordinator相关的领域对象。
 * Platform-owned coordinator for the two bounded, immutable first-phase workflow graphs. */
@Service
public class WorkflowRunCoordinator {

    private static final int MAX_RUNTIME_BYTES = 256 * 1024;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final WorkflowRunMapper workflowMapper;
    private final TaskRunCommandMapper runMapper;
    private final NotificationApplicationService notificationService;
    private final ObjectProvider<TaskRunExecutionCoordinator> executionCoordinatorProvider;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code WorkflowRunCoordinator} 实例并初始化所需依赖。
     *
     * @param workflowMapper 工作流Mapper参数
     * @param runMapper {@code runMapper}参数
     * @param notificationService 通知Service参数
     * @param executionCoordinatorProvider 执行Coordinator提供方参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public WorkflowRunCoordinator(
        WorkflowRunMapper workflowMapper,
        TaskRunCommandMapper runMapper,
        NotificationApplicationService notificationService,
        ObjectProvider<TaskRunExecutionCoordinator> executionCoordinatorProvider,
        JsonMapper jsonMapper
    ) {
        this.workflowMapper = workflowMapper;
        this.runMapper = runMapper;
        this.notificationService = notificationService;
        this.executionCoordinatorProvider = executionCoordinatorProvider;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code owns}并返回对应结果。
     *
     * @param request 请求参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean owns(AgentRunRequest request) {
        return positiveNumber(request.attributes().get("workflowVersionId")) != null
            && request.attributes().get("workflowContentHash") instanceof String hash
            && hash.matches("[a-f0-9]{64}");
    }

    /**
     * 处理{@code owns}并返回对应结果。
     *
     * @param request 请求参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean owns(AgentResumeRequest request) {
        WorkflowRunStepRow step = workflowMapper.selectStep(request.runId(), request.stepId());
        if (step == null || step.getRoleKey() == null || step.getRuntimeSnapshotJson() == null) {
            return false;
        }
        AgentRunRequest frozen = jsonMapper.readValue(
            step.getRuntimeSnapshotJson(), AgentRunRequest.class
        );
        return owns(frozen) && request.executionKey().equals(frozen.executionKey())
            && request.taskId().equals(frozen.taskId());
    }

    /**
     * 处理{@code frozen}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public AgentRunRequest frozen(AgentResumeRequest request) {
        WorkflowRunStepRow step = workflowMapper.selectStep(request.runId(), request.stepId());
        if (step == null || step.getRuntimeSnapshotJson() == null) {
            throw new SecurityException("工作流恢复缺少步骤运行快照");
        }
        AgentRunRequest frozen = jsonMapper.readValue(
            step.getRuntimeSnapshotJson(), AgentRunRequest.class
        );
        if (!owns(frozen) || !request.executionKey().equals(frozen.executionKey())
            || !request.taskId().equals(frozen.taskId())) {
            throw new SecurityException("工作流恢复身份与步骤快照不一致");
        }
        return frozen;
    }

    /**
     * 判断{@code WaitingStep}是否满足要求。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param waitReason {@code waitReason}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean isWaitingStep(Long runId, Long stepId, String waitReason) {
        WorkflowRunStepRow step = workflowMapper.selectStep(runId, stepId);
        return step != null && "waiting".equals(step.getStatus())
            && waitReason.equals(step.getWaitReason()) && step.getRuntimeSnapshotJson() != null;
    }

    /**
     * 处理{@code resumeWaitingStep}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean resumeWaitingStep(Long runId, Long stepId) {
        if (workflowMapper.resumeStep(runId, stepId) != 1) {
            return false;
        }
        workflowMapper.projectRunStatus(runId, "running", null);
        return true;
    }

    /**
     * 处理{@code pauseStepsAfterCommit}相关逻辑。
     *
     * @param runId 资源标识
     * @param reason {@code reason}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void pauseStepsAfterCommit(Long runId, String reason) {
        List<AgentRunRequest> active = activeRequests(runId).stream()
            .filter(request -> {
                WorkflowRunStepRow step = workflowMapper.selectStep(runId, request.stepId());
                return step != null && "running".equals(step.getStatus());
            }).toList();
        workflowMapper.pauseRunningSteps(runId);
        cancelAfterCommit(active, reason);
    }

    /**
     * 处理{@code resumePausedAfterCommit}并返回对应结果。
     *
     * @param runId 资源标识
     * @param actorId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean resumePausedAfterCommit(Long runId, Long actorId) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<WorkflowRunStepRow> steps = workflowMapper.lockSteps(runId);
        List<AgentResumeRequest> resumes = new ArrayList<>();
        for (WorkflowRunStepRow step : steps) {
            if (!"waiting".equals(step.getStatus()) || !"user_pause".equals(step.getWaitReason())
                || step.getRuntimeSnapshotJson() == null) {
                continue;
            }
            AgentRunRequest frozen = jsonMapper.readValue(
                step.getRuntimeSnapshotJson(), AgentRunRequest.class
            );
            if (!owns(frozen) || workflowMapper.resumeStep(runId, step.getId()) != 1) {
                throw new IllegalStateException("暂停的工作流步骤不能恢复");
            }
            resumes.add(new AgentResumeRequest(
                frozen.executionKey(), frozen.userId(), frozen.conversationId(), frozen.taskId(),
                frozen.runId(), frozen.stepId(), frozen.sessionId(),
                "manual-resume-" + runId + '-' + frozen.stepId(),
                RuntimeResumeDecision.APPROVE, Map.of(),
                Map.of("actorId", actorId, "reason", "manual_resume"),
                RuntimeResumeMode.CONTINUE
            ).withRuntimeContext(frozen));
        }
        if (resumes.isEmpty()) {
            return false;
        }
        workflowMapper.projectRunStatus(runId, "running", null);
        launchResumesAfterCommit(resumes);
        return true;
    }

    /**
     * 判断{@code celActiveAfterCommit}是否满足要求。
     *
     * @param runId 资源标识
     * @param reason {@code reason}参数
     */
    public void cancelActiveAfterCommit(Long runId, String reason) {
        cancelAfterCommit(activeRequests(runId), reason);
    }

    /**
     * 处理{@code startReadyAfterCommit}相关逻辑。
     *
     * @param runId 资源标识
     * @param taskId 资源标识
     * @param workerId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void startReadyAfterCommit(Long runId, Long taskId, String workerId) {
        scheduleReady(runId, taskId, workerId);
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
        switch (event.eventType()) {
            case "result" -> recordOutput(request, event, source);
            case "approval_required" -> markWaiting(
                request, workerId, "waiting_approval", "tool_approval"
            );
            case "external_execution_required" -> markWaiting(
                request, workerId, "waiting_input", "external_execution"
            );
            case "run_finished" -> markSucceeded(request, workerId, event, source);
            case "failed" -> fail(
                request, "RUNTIME_FAILED", event.summary()
            );
            case "permission_denied" -> fail(
                request, "RUNTIME_PERMISSION_DENIED", event.summary()
            );
            case "iteration_limit_reached" -> fail(
                request, "RUNTIME_ITERATION_LIMIT", event.summary()
            );
            case "cancelled" -> cancelled(request, event.summary());
            default -> runMapper.renewLease(request.runId(), workerId);
        }
    }

    /**
     * 处理{@code onFailure}相关逻辑。
     *
     * @param request 请求参数
     * @param errorCode {@code errorCode}参数
     * @param errorSummary {@code errorSummary}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void onFailure(
        AgentRunRequest request,
        String errorCode,
        String errorSummary
    ) {
        fail(request, errorCode, errorSummary);
    }

    /**
     * 处理{@code onUnexpectedCompletion}相关逻辑。
     *
     * @param request 请求参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void onUnexpectedCompletion(AgentRunRequest request) {
        fail(
            request,
            "RUNTIME_STREAM_ENDED",
            "运行事件流结束，但工作流步骤没有收到完成、等待或失败事件"
        );
    }

    /**
     * 处理{@code recordOutput}相关逻辑。
     *
     * @param request 请求参数
     * @param event 事件参数
     * @param source 数据源参数
     */
    private void recordOutput(
        AgentRunRequest request,
        ExecutionEventView event,
        RuntimeEvent source
    ) {
        Map<String, Object> output = source == null ? event.payload() : source.payload();
        String outputJson = boundedOutput(output, event.summary());
        workflowMapper.recordOutput(
            request.runId(), request.stepId(), safeSummary(event.summary()), outputJson
        );
    }

    /**
     * 处理{@code markWaiting}相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     * @param runStatus 目标状态
     * @param waitReason {@code waitReason}参数
     */
    private void markWaiting(
        AgentRunRequest request,
        String workerId,
        String runStatus,
        String waitReason
    ) {
        if (workflowMapper.markWaiting(request.runId(), request.stepId(), waitReason) == 1) {
            workflowMapper.projectRunStatus(request.runId(), runStatus, waitReason);
        }
    }

    /**
     * 处理{@code markSucceeded}相关逻辑。
     *
     * @param request 请求参数
     * @param workerId 资源标识
     * @param event 事件参数
     * @param source 数据源参数
     */
    private void markSucceeded(
        AgentRunRequest request,
        String workerId,
        ExecutionEventView event,
        RuntimeEvent source
    ) {
        WorkflowRunStepRow current = workflowMapper.selectStep(request.runId(), request.stepId());
        if (current != null && current.getOutputJson() == null) {
            recordOutput(request, event, source);
        }
        if (workflowMapper.markSucceeded(request.runId(), request.stepId()) == 1) {
            scheduleReady(request.runId(), request.taskId(), workerId);
        }
    }

    /**
     * 处理{@code fail}相关逻辑。
     *
     * @param request 请求参数
     * @param errorCode {@code errorCode}参数
     * @param errorSummary {@code errorSummary}参数
     */
    private void fail(AgentRunRequest request, String errorCode, String errorSummary) {
        String summary = safeSummary(errorSummary);
        List<AgentRunRequest> active = activeRequests(request.runId());
        if (workflowMapper.failRunningStep(
            request.runId(), request.stepId(), errorCode, summary
        ) != 1) {
            return;
        }
        if (runMapper.failRun(request.runId(), errorCode, summary) == 1) {
            runMapper.cancelSteps(request.runId());
            runMapper.markTaskBlocked(request.taskId(), request.runId());
            notifyRun(request.taskId(), request.runId(), "failed", "error", "多智能体任务运行失败");
            cancelAfterCommit(active, "workflow_fail_fast");
        }
    }

    /**
     * 判断{@code celled}是否满足要求。
     *
     * @param request 请求参数
     * @param reason {@code reason}参数
     */
    private void cancelled(AgentRunRequest request, String reason) {
        String status = workflowMapper.selectRunStatus(request.runId());
        if ("paused".equals(status) || "cancelled".equals(status)) {
            return;
        }
        String safeReason = safeSummary(reason);
        if (runMapper.cancelRun(request.taskId(), request.runId(), safeReason) == 1) {
            runMapper.cancelSteps(request.runId());
            runMapper.markTaskCancelled(request.taskId(), request.runId(), request.userId());
            notifyRun(request.taskId(), request.runId(), "cancelled", "warning", "多智能体任务运行已取消");
        }
    }

    /**
     * 处理调度Ready相关逻辑。
     *
     * @param runId 资源标识
     * @param taskId 资源标识
     * @param workerId 资源标识
     */
    private void scheduleReady(Long runId, Long taskId, String workerId) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<WorkflowRunStepRow> steps = workflowMapper.lockSteps(runId);
        if (steps.isEmpty() || steps.stream().anyMatch(step -> Set.of(
            "failed", "cancelled"
        ).contains(step.getStatus()))) {
            return;
        }
        Map<String, WorkflowRunStepRow> byKey = new LinkedHashMap<>();
        steps.forEach(step -> byKey.put(step.getStepKey(), step));
        int maxParallelism = workflowLimits(steps).maxParallelism();
        int running = (int) steps.stream().filter(step -> "running".equals(step.getStatus())).count();
        List<AgentRunRequest> launches = new ArrayList<>();

        boolean aggregateProgress;
        do {
            aggregateProgress = false;
            for (WorkflowRunStepRow step : steps) {
                if (!"pending".equals(step.getStatus()) || !ready(step, byKey)) {
                    continue;
                }
                if ("aggregate".equals(step.getStepType())) {
                    DependencyContext context = dependencyContext(step, byKey, workflowLimits(steps));
                    if (workflowMapper.completeAggregate(
                        runId, step.getId(), "依赖分支已汇总，等待人工验收", context.json()
                    ) == 1) {
                        step.setStatus("succeeded");
                        step.setOutputSummary("依赖分支已汇总，等待人工验收");
                        step.setOutputJson(context.json());
                        aggregateProgress = true;
                    }
                }
            }
        } while (aggregateProgress);

        for (WorkflowRunStepRow step : steps) {
            if (running >= maxParallelism) {
                break;
            }
            if (!"pending".equals(step.getStatus()) || !"agent".equals(step.getStepType())
                || !ready(step, byKey)) {
                continue;
            }
            AgentRunRequest request = materialize(step, byKey, workflowLimits(steps));
            String inputJson = jsonMapper.writeValueAsString(Map.of("text", request.input()));
            if (workflowMapper.materializeAndStart(
                runId, step.getId(), jsonMapper.writeValueAsString(request), inputJson,
                safeInputSummary(request.input())
            ) != 1) {
                continue;
            }
            step.setStatus("running");
            step.setRuntimeSnapshotJson(jsonMapper.writeValueAsString(request));
            launches.add(request);
            running++;
        }

        boolean complete = steps.stream().allMatch(step -> "succeeded".equals(step.getStatus()));
        if (complete) {
            if (workflowMapper.finishRun(runId, taskId) == 1) {
                runMapper.markTaskVerifying(taskId, runId);
                notifyRun(taskId, runId, "succeeded", "success", "多智能体任务运行已完成，等待验收");
            }
        } else {
            String waitingStatus = projectedWaitingStatus(steps);
            if (waitingStatus == null) {
                workflowMapper.projectRunStatus(runId, "running", null);
            } else {
                workflowMapper.projectRunStatus(
                    runId, waitingStatus,
                    "waiting_approval".equals(waitingStatus) ? "tool_approval" : "external_execution"
                );
            }
        }
        launchAfterCommit(launches);
    }

    /**
     * 处理{@code materialize}并返回对应结果。
     *
     * @param step {@code step}参数
     * @param byKey {@code byKey}参数
     * @param limits 数量上限
     * @return 处理结果
     */
    private AgentRunRequest materialize(
        WorkflowRunStepRow step,
        Map<String, WorkflowRunStepRow> byKey,
        WorkflowLimits limits
    ) {
        if (step.getRuntimeTemplateJson() == null) {
            throw new IllegalStateException("工作流Agent步骤缺少冻结运行模板");
        }
        AgentRunRequest template = jsonMapper.readValue(
            step.getRuntimeTemplateJson(), AgentRunRequest.class
        );
        requireFrozenIdentity(step, template, limits);
        DependencyContext dependencies = dependencyContext(step, byKey, limits);
        String input = template.input();
        if (!dependencies.keys().isEmpty()) {
            input += "\n\nAuthorized dependency outputs (bounded JSON):\n" + dependencies.json();
        }
        Map<String, Object> attributes = new LinkedHashMap<>(template.attributes());
        attributes.put("workflowDependencyStepKeys", dependencies.keys());
        attributes.put("workflowDependencyHash", ContentHashing.sha256(dependencies.json()));
        AgentRunRequest request = new AgentRunRequest(
            template.executionKey(), template.userId(), template.conversationId(), template.taskId(),
            template.runId(), template.stepId(), template.agentVersionId(), template.agentName(),
            template.sessionId(), input, template.systemPrompt(), template.model(),
            template.workspaceKey(), template.maxIterations(), template.authorizationSnapshot(),
            attributes
        );
        if (jsonMapper.writeValueAsBytes(request).length > MAX_RUNTIME_BYTES) {
            throw new IllegalStateException("工作流步骤运行快照超过256KB限制");
        }
        return request;
    }

    /**
     * 处理dependency上下文并返回对应结果。
     *
     * @param step {@code step}参数
     * @param byKey {@code byKey}参数
     * @param limits 数量上限
     * @return 处理结果
     */
    private DependencyContext dependencyContext(
        WorkflowRunStepRow step,
        Map<String, WorkflowRunStepRow> byKey,
        WorkflowLimits limits
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<String> dependencies = dependencies(step);
        Map<String, Object> output = new LinkedHashMap<>();
        for (String key : dependencies) {
            WorkflowRunStepRow dependency = byKey.get(key);
            Object value = parseOutput(dependency == null ? null : dependency.getOutputJson());
            output.put(key, value);
        }
        String json = jsonMapper.writeValueAsString(output);
        if (json.getBytes(StandardCharsets.UTF_8).length > limits.maxDependencyBytes()) {
            Map<String, Object> bounded = new LinkedHashMap<>();
            for (String key : dependencies) {
                WorkflowRunStepRow dependency = byKey.get(key);
                String raw = dependency == null || dependency.getOutputJson() == null
                    ? "{}" : dependency.getOutputJson();
                bounded.put(key, Map.of(
                    "summary", safeSummary(dependency == null ? null : dependency.getOutputSummary()),
                    "contentHash", ContentHashing.sha256(raw),
                    "truncated", true
                ));
            }
            json = jsonMapper.writeValueAsString(bounded);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > limits.maxDependencyBytes()) {
            throw new IllegalStateException("工作流依赖输出超过冻结限制");
        }
        return new DependencyContext(List.copyOf(dependencies), json);
    }

    /**
     * 处理{@code ready}并返回对应结果。
     *
     * @param step {@code step}参数
     * @param byKey {@code byKey}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean ready(
        WorkflowRunStepRow step,
        Map<String, WorkflowRunStepRow> byKey
    ) {
        for (String dependency : dependencies(step)) {
            WorkflowRunStepRow row = byKey.get(dependency);
            if (row == null || !"succeeded".equals(row.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 处理{@code dependencies}并返回对应结果。
     *
     * @param step {@code step}参数
     * @return 符合条件的数据集合
     */
    private List<String> dependencies(WorkflowRunStepRow step) {
        try {
            List<String> values = jsonMapper.readValue(step.getDependsOnJson(), STRING_LIST);
            return values == null ? List.of() : values;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("工作流步骤依赖快照无效", exception);
        }
    }

    /**
     * 处理工作流Limits并返回对应结果。
     *
     * @param steps {@code steps}参数
     * @return 处理结果
     */
    private WorkflowLimits workflowLimits(List<WorkflowRunStepRow> steps) {
        WorkflowRunStepRow agent = steps.stream()
            .filter(step -> step.getRuntimeTemplateJson() != null)
            .findFirst().orElseThrow(() -> new IllegalStateException("工作流没有Agent运行模板"));
        AgentRunRequest request = jsonMapper.readValue(
            agent.getRuntimeTemplateJson(), AgentRunRequest.class
        );
        Integer parallelism = integerAttribute(request, "workflowMaxParallelism");
        Integer dependencyBytes = integerAttribute(request, "workflowMaxDependencyBytes");
        String hash = String.valueOf(request.attributes().get("workflowContentHash"));
        if (parallelism == null || parallelism < 1 || parallelism > 3
            || dependencyBytes == null || dependencyBytes < 1024 || dependencyBytes > 65536
            || !hash.matches("[a-f0-9]{64}")) {
            throw new SecurityException("工作流运行限制快照无效");
        }
        return new WorkflowLimits(parallelism, dependencyBytes, hash);
    }

    /**
     * 校验Frozen身份，并在条件不满足时终止处理。
     *
     * @param step {@code step}参数
     * @param request 请求参数
     * @param limits 数量上限
     */
    private void requireFrozenIdentity(
        WorkflowRunStepRow step,
        AgentRunRequest request,
        WorkflowLimits limits
    ) {
        if (!step.getRunId().equals(request.runId()) || !step.getId().equals(request.stepId())
            || !step.getAgentVersionId().equals(request.agentVersionId())
            || !step.getStepKey().equals(request.attributes().get("workflowNodeKey"))
            || !step.getRoleKey().equals(request.attributes().get("workflowRole"))
            || !limits.contentHash().equals(request.attributes().get("workflowContentHash"))) {
            throw new SecurityException("工作流步骤运行模板身份不一致");
        }
    }

    /**
     * 处理{@code projectedWaitingStatus}并返回对应结果。
     *
     * @param steps {@code steps}参数
     * @return 处理结果
     */
    private String projectedWaitingStatus(List<WorkflowRunStepRow> steps) {
        Set<String> reasons = new LinkedHashSet<>();
        steps.stream().filter(step -> "waiting".equals(step.getStatus()))
            .map(WorkflowRunStepRow::getWaitReason).forEach(reasons::add);
        if (reasons.contains("tool_approval")) {
            return "waiting_approval";
        }
        if (reasons.contains("external_execution")) {
            return "waiting_input";
        }
        return null;
    }

    /**
     * 处理{@code activeRequests}并返回对应结果。
     *
     * @param runId 资源标识
     * @return 符合条件的数据集合
     */
    private List<AgentRunRequest> activeRequests(Long runId) {
        List<AgentRunRequest> result = new ArrayList<>();
        for (WorkflowRunStepRow step : workflowMapper.selectActiveSteps(runId)) {
            if (step.getRuntimeSnapshotJson() != null) {
                result.add(jsonMapper.readValue(step.getRuntimeSnapshotJson(), AgentRunRequest.class));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code launchAfterCommit}相关逻辑。
     *
     * @param requests {@code requests}参数
     */
    private void launchAfterCommit(List<AgentRunRequest> requests) {
        if (requests.isEmpty()) {
            return;
        }
        afterCommit(() -> {
            TaskRunExecutionCoordinator coordinator = executionCoordinatorProvider.getIfAvailable();
            if (coordinator == null) {
                requests.forEach(request -> onFailure(
                    request, "RUNTIME_LAUNCH_FAILED", "AgentScope运行时未启用"
                ));
                return;
            }
            requests.forEach(coordinator::launchOrMarkFailed);
        });
    }

    /**
     * 处理{@code launchResumesAfterCommit}相关逻辑。
     *
     * @param requests {@code requests}参数
     */
    private void launchResumesAfterCommit(List<AgentResumeRequest> requests) {
        afterCommit(() -> {
            TaskRunExecutionCoordinator coordinator = executionCoordinatorProvider.getIfAvailable();
            if (coordinator == null) {
                throw new IllegalStateException("AgentScope运行时未启用");
            }
            requests.forEach(coordinator::launchResumeOrMarkFailed);
        });
    }

    /**
     * 判断{@code celAfterCommit}是否满足要求。
     *
     * @param requests {@code requests}参数
     * @param reason {@code reason}参数
     */
    private void cancelAfterCommit(List<AgentRunRequest> requests, String reason) {
        afterCommit(() -> {
            TaskRunExecutionCoordinator coordinator = executionCoordinatorProvider.getIfAvailable();
            if (coordinator != null) {
                requests.forEach(request -> coordinator.requestCancellation(request, reason));
            }
        });
    }

    /**
     * 处理{@code afterCommit}相关逻辑。
     *
     * @param action {@code action}参数
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 处理{@code afterCommit}相关逻辑。
             */
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * 处理{@code parseOutput}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Object parseOutput(String value) {
        return value == null || value.isBlank()
            ? Map.of() : jsonMapper.readValue(value, Object.class);
    }

    /**
     * 处理{@code boundedOutput}并返回对应结果。
     *
     * @param output {@code output}参数
     * @param summary {@code summary}参数
     * @return 处理结果
     */
    private String boundedOutput(Map<String, Object> output, String summary) {
        String json = jsonMapper.writeValueAsString(output == null ? Map.of() : output);
        if (json.getBytes(StandardCharsets.UTF_8).length <= 64 * 1024) {
            return json;
        }
        return jsonMapper.writeValueAsString(Map.of(
            "summary", safeSummary(summary),
            "contentHash", ContentHashing.sha256(json),
            "truncated", true
        ));
    }

    /**
     * 处理{@code integerAttribute}并返回对应结果。
     *
     * @param request 请求参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private Integer integerAttribute(AgentRunRequest request, String key) {
        Object raw = request.attributes().get(key);
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()) {
            return null;
        }
        return number.intValue();
    }

    /**
     * 处理{@code positiveNumber}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private Long positiveNumber(Object raw) {
        if (!(raw instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            return null;
        }
        return number.longValue();
    }

    /**
     * 处理{@code safeInputSummary}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeInputSummary(String value) {
        return value.length() <= 512 ? value : value.substring(0, 512);
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
            "run:" + event + ':' + runId,
            "run", level, title, "请打开任务运行查看详情", "run", runId
        ));
    }

    /**
     * 封装工作流Limits相关的不可变数据。
     */
    private record WorkflowLimits(int maxParallelism, int maxDependencyBytes, String contentHash) {
    }

    /**
     * 封装{@code Dependency}相关的不可变数据。
     */
    private record DependencyContext(List<String> keys, String json) {
    }
}
