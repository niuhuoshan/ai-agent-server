package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.RuntimeCancellationResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * 表示任务Run执行Coordinator相关的领域对象。
 * Owns local virtual-thread execution while database claims prevent cross-instance duplicates. */
@Service
public class TaskRunExecutionCoordinator {

    private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectProvider<AgentRuntimeExecutionService> runtimeProvider;
    private final TaskRunStateService stateService;
    private final Executor executor;
    private final String workerId;
    private final ConcurrentMap<ExecutionSlot, Boolean> activeExecutions = new ConcurrentHashMap<>();

    @Autowired
    public TaskRunExecutionCoordinator(
        ObjectProvider<AgentRuntimeExecutionService> runtimeProvider,
        TaskRunStateService stateService
    ) {
        this(
            runtimeProvider,
            stateService,
            command -> Thread.ofVirtual().name("agent-task-run").start(command),
            "agent-worker-" + UUID.randomUUID().toString().replace("-", "")
        );
    }

    /**
     * 创建 {@code TaskRunExecutionCoordinator} 实例并初始化所需依赖。
     *
     * @param runtimeProvider 运行时提供方参数
     * @param stateService {@code stateService}参数
     * @param executor {@code executor}参数
     * @param workerId 资源标识
     */
    TaskRunExecutionCoordinator(
        ObjectProvider<AgentRuntimeExecutionService> runtimeProvider,
        TaskRunStateService stateService,
        Executor executor,
        String workerId
    ) {
        this.runtimeProvider = runtimeProvider;
        this.stateService = stateService;
        this.executor = executor;
        this.workerId = workerId;
    }

    /**
     * 处理{@code available}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean available() {
        return runtimeProvider.getIfAvailable() != null;
    }

    /**
     * 处理工作进程Id并返回对应结果。
     *
     * @return 处理结果
     */
    public String workerId() {
        return workerId;
    }

    /**
     * 处理{@code launch}相关逻辑。
     *
     * @param request 请求参数
     */
    public void launch(AgentRunRequest request) {
        ExecutionSlot slot = slot(request);
        if (activeExecutions.putIfAbsent(slot, Boolean.TRUE) != null) {
            return;
        }
        AgentRuntimeExecutionService runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            activeExecutions.remove(slot);
            throw new IllegalStateException("AgentScope运行时未启用");
        }
        try {
            executor.execute(() -> execute(runtime, request));
        } catch (RuntimeException exception) {
            activeExecutions.remove(slot);
            throw exception;
        }
    }

    /**
 * 处理{@code launchResumeOrMarkFailed}相关逻辑。
 * Schedules a resumed invocation and projects scheduling failures to the durable run. */
    public void launchResumeOrMarkFailed(AgentResumeRequest request) {
        ExecutionSlot slot = slot(request);
        if (activeExecutions.putIfAbsent(slot, Boolean.TRUE) != null) {
            return;
        }
        AgentRuntimeExecutionService runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            activeExecutions.remove(slot);
            stateService.onLaunchFailure(
                request.runId(), request.taskId(), request.stepId(), workerId,
                new IllegalStateException("AgentScope运行时未启用")
            );
            return;
        }
        try {
            executor.execute(() -> executeResume(runtime, request));
        } catch (RuntimeException exception) {
            activeExecutions.remove(slot);
            stateService.onLaunchFailure(
                request.runId(), request.taskId(), request.stepId(), workerId, exception
            );
        }
    }

    /**
 * 处理{@code launchOrMarkFailed}相关逻辑。
 * Converts an after-commit launch error into a terminal, auditable run state. */
    public void launchOrMarkFailed(AgentRunRequest request) {
        try {
            launch(request);
        } catch (RuntimeException exception) {
            if (request.attributes().get("workflowVersionId") instanceof Number) {
                stateService.onLaunchFailure(request, workerId, exception);
            } else {
                stateService.onLaunchFailure(
                    request.runId(), request.taskId(), request.stepId(), workerId, exception
                );
            }
        }
    }

    /**
     * 处理{@code requestCancellation}并返回对应结果。
     *
     * @param request 请求参数
     * @param reason {@code reason}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean requestCancellation(AgentRunRequest request, String reason) {
        AgentRuntimeExecutionService runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            return false;
        }
        RuntimeCancellationResult result = runtime.cancel(request.executionKey(), reason)
            .block(CANCEL_TIMEOUT);
        return result != null && result.interruptRequested();
    }

    /**
     * 判断{@code LocallyActive}是否满足要求。
     *
     * @param runId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean isLocallyActive(Long runId) {
        return activeExecutions.keySet().stream().anyMatch(slot -> slot.runId().equals(runId));
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param runtime 运行时参数
     * @param request 请求参数
     */
    private void execute(AgentRuntimeExecutionService runtime, AgentRunRequest request) {
        executeStream(
            request,
            () -> runtime.runInternal(request)
        );
    }

    /**
     * 执行{@code Resume}相关的处理流程。
     *
     * @param runtime 运行时参数
     * @param request 请求参数
     */
    private void executeResume(AgentRuntimeExecutionService runtime, AgentResumeRequest request) {
        executeStream(
            request,
            () -> runtime.resumeInternal(request)
        );
    }

    /**
     * 执行{@code Stream}相关的处理流程。
     *
     * @param request 请求参数
     * @param stream {@code stream}参数
     */
    private void executeStream(
        AgentRunRequest request,
        java.util.function.Supplier<reactor.core.publisher.Flux<PersistedRuntimeEvent>> stream
    ) {
        try {
            stream.get()
                .doOnNext(event -> stateService.onEvent(
                    request, workerId, event.view(), event.source()
                ))
                .doOnError(error -> stateService.onFailure(request, workerId, error))
                .blockLast();
            stateService.onUnexpectedCompletion(request, workerId);
        } catch (RuntimeException exception) {
            stateService.onFailure(request, workerId, exception);
        } finally {
            activeExecutions.remove(slot(request));
        }
    }

    /**
     * 执行{@code Stream}相关的处理流程。
     *
     * @param request 请求参数
     * @param stream {@code stream}参数
     */
    private void executeStream(
        AgentResumeRequest request,
        java.util.function.Supplier<reactor.core.publisher.Flux<PersistedRuntimeEvent>> stream
    ) {
        try {
            stream.get()
                .doOnNext(event -> stateService.onResumeEvent(
                    request, workerId, event.view(), event.source()
                ))
                .doOnError(error -> stateService.onResumeFailure(request, workerId, error))
                .blockLast();
            stateService.onResumeUnexpectedCompletion(request, workerId);
        } catch (RuntimeException exception) {
            stateService.onResumeFailure(request, workerId, exception);
        } finally {
            activeExecutions.remove(slot(request));
        }
    }

    /**
     * 处理{@code slot}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private ExecutionSlot slot(AgentRunRequest request) {
        return new ExecutionSlot(request.runId(), request.stepId(), request.executionKey().executionId());
    }

    /**
     * 处理{@code slot}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private ExecutionSlot slot(AgentResumeRequest request) {
        return new ExecutionSlot(request.runId(), request.stepId(), request.executionKey().executionId());
    }

    /**
     * 封装执行Slot相关的不可变数据。
     */
    private record ExecutionSlot(Long runId, Long stepId, String executionId) {
    }
}
