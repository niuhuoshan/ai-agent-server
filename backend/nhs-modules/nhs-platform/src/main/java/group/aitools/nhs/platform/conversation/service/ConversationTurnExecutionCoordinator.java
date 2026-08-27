package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.RuntimeCancellationResult;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.platform.execution.service.AgentRuntimeExecutionService;
import group.aitools.nhs.platform.execution.service.PersistedRuntimeEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * 表示会话会话回合执行Coordinator相关的领域对象。
 * Runs human conversation turns independently of the browser connection. */
@Service
public class ConversationTurnExecutionCoordinator {

    private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_RESPONSE_CHARS = 1024 * 1024;

    private final ObjectProvider<AgentRuntimeExecutionService> runtimeProvider;
    private final ConversationTurnPersistenceService persistence;
    private final Executor executor;
    private final ConcurrentMap<Long, group.aitools.nhs.runtime.spi.RuntimeExecutionKey> active = new ConcurrentHashMap<>();

    @Autowired
    public ConversationTurnExecutionCoordinator(
        ObjectProvider<AgentRuntimeExecutionService> runtimeProvider,
        ConversationTurnPersistenceService persistence
    ) {
        this(
            runtimeProvider, persistence,
            command -> Thread.ofVirtual().name("agent-conversation-turn").start(command)
        );
    }

    /**
     * 创建 {@code ConversationTurnExecutionCoordinator} 实例并初始化所需依赖。
     *
     * @param runtimeProvider 运行时提供方参数
     * @param persistence {@code persistence}参数
     * @param executor {@code executor}参数
     */
    ConversationTurnExecutionCoordinator(
        ObjectProvider<AgentRuntimeExecutionService> runtimeProvider,
        ConversationTurnPersistenceService persistence,
        Executor executor
    ) {
        this.runtimeProvider = runtimeProvider;
        this.persistence = persistence;
        this.executor = executor;
    }

    /**
     * 处理{@code launch}相关逻辑。
     *
     * @param turnId 资源标识
     * @param request 请求参数
     */
    public void launch(Long turnId, AgentRunRequest request) {
        if (active.putIfAbsent(turnId, request.executionKey()) != null) {
            return;
        }
        AgentRuntimeExecutionService runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            active.remove(turnId, request.executionKey());
            throw new IllegalStateException("AgentScope运行时未启用");
        }
        try {
            executor.execute(() -> execute(turnId, runtime, request));
        } catch (RuntimeException exception) {
            active.remove(turnId, request.executionKey());
            throw exception;
        }
    }

    /**
 * 处理{@code launchResume}相关逻辑。
 * Launches the original private conversation runtime after a confirmation decision. */
    public void launchResume(
        Long turnId,
        AgentResumeRequest request,
        AgentRunRequest frozen
    ) {
        if (active.putIfAbsent(turnId, request.executionKey()) != null) {
            return;
        }
        AgentRuntimeExecutionService runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            active.remove(turnId, request.executionKey());
            throw new IllegalStateException("AgentScope运行时未启用");
        }
        try {
            executor.execute(() -> executeResume(turnId, runtime, request, frozen));
        } catch (RuntimeException exception) {
            active.remove(turnId, request.executionKey());
            throw exception;
        }
    }

    /**
     * 处理{@code requestStop}并返回对应结果。
     *
     * @param turnId 资源标识
     * @param reason {@code reason}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean requestStop(Long turnId, String reason) {
        group.aitools.nhs.runtime.spi.RuntimeExecutionKey executionKey = active.get(turnId);
        AgentRuntimeExecutionService runtime = runtimeProvider.getIfAvailable();
        if (executionKey == null || runtime == null) {
            return false;
        }
        RuntimeCancellationResult result = runtime.cancel(executionKey, reason)
            .block(CANCEL_TIMEOUT);
        return result != null && result.interruptRequested();
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param turnId 资源标识
     * @param runtime 运行时参数
     * @param request 请求参数
     */
    private void execute(
        Long turnId,
        AgentRuntimeExecutionService runtime,
        AgentRunRequest request
    ) {
        StringBuilder response = new StringBuilder();
        Terminal terminal = new Terminal();
        Thread cancellationWatcher = Thread.ofVirtual()
            .name("agent-conversation-cancel-watch-" + turnId)
            .start(() -> watchCancellation(turnId, runtime, request.executionKey()));
        try {
            runtime.runInternal(request)
                .doOnNext(event -> accept(event, response, terminal))
                .blockLast();
            if (terminal.approval != null
                && persistence.suspendForConfirmation(turnId, request, terminal.approval, response.toString())) {
                return;
            }
            if (terminal.userQuestion != null
                && persistence.suspendForUserQuestion(
                    turnId, request, terminal.userQuestion, response.toString()
                )) {
                return;
            }
            finish(turnId, response, terminal);
        } catch (RuntimeException exception) {
            persistence.finish(turnId, "failed", response.toString(), exception);
        } finally {
            cancellationWatcher.interrupt();
            active.remove(turnId, request.executionKey());
        }
    }

    /**
     * 执行{@code Resume}相关的处理流程。
     *
     * @param turnId 资源标识
     * @param runtime 运行时参数
     * @param request 请求参数
     * @param frozen {@code frozen}参数
     */
    private void executeResume(
        Long turnId,
        AgentRuntimeExecutionService runtime,
        AgentResumeRequest request,
        AgentRunRequest frozen
    ) {
        StringBuilder response = new StringBuilder();
        Terminal terminal = new Terminal();
        Thread cancellationWatcher = Thread.ofVirtual()
            .name("agent-conversation-cancel-watch-" + turnId)
            .start(() -> watchCancellation(turnId, runtime, request.executionKey()));
        try {
            runtime.resumeInternal(request)
                .doOnNext(event -> accept(event, response, terminal))
                .blockLast();
            if (terminal.approval != null
                && persistence.suspendForConfirmation(turnId, frozen, terminal.approval, response.toString())) {
                return;
            }
            if (terminal.userQuestion != null
                && persistence.suspendForUserQuestion(
                    turnId, frozen, terminal.userQuestion, response.toString()
                )) {
                return;
            }
            finish(turnId, response, terminal);
        } catch (RuntimeException exception) {
            persistence.finish(turnId, "failed", response.toString(), exception);
        } finally {
            cancellationWatcher.interrupt();
            active.remove(turnId, request.executionKey());
        }
    }

    /**
     * 处理{@code finish}相关逻辑。
     *
     * @param turnId 资源标识
     * @param response {@code response}参数
     * @param terminal {@code terminal}参数
     */
    private void finish(Long turnId, StringBuilder response, Terminal terminal) {
        String status = terminal.cancelled ? "cancelled"
            : terminal.failed || terminal.approval != null ? "failed" : "succeeded";
        Throwable failure = terminal.approval == null ? terminal.failure
            : new IllegalStateException("确认操作未能恢复原会话运行");
        persistence.finish(turnId, status, response.toString(), failure);
    }

    /**
 * 处理{@code watchCancellation}相关逻辑。
 * Polls the DB stop fact so a worker can be interrupted by another JVM. */
    private void watchCancellation(
        Long turnId,
        AgentRuntimeExecutionService runtime,
        group.aitools.nhs.runtime.spi.RuntimeExecutionKey executionKey
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (persistence.stopRequested(turnId)) {
                    runtime.cancel(executionKey, "用户停止会话回复")
                        .block(CANCEL_TIMEOUT);
                    return;
                }
                Thread.sleep(250L);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // The execution thread remains authoritative; a transient poll
            // failure must not terminate a running Agent invocation.
        }
    }

    /**
     * 处理{@code accept}相关逻辑。
     *
     * @param persisted {@code persisted}参数
     * @param response {@code response}参数
     * @param terminal {@code terminal}参数
     */
    private void accept(PersistedRuntimeEvent persisted, StringBuilder response, Terminal terminal) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        var event = persisted.source();
        if (event.type() == RuntimeEventType.CUSTOM
            && Boolean.TRUE.equals(event.payload().get("retraction"))) {
            response.setLength(0);
            response.append(event.summary());
        } else if (event.type() == RuntimeEventType.TEXT_DELTA && response.length() < MAX_RESPONSE_CHARS) {
            int remaining = MAX_RESPONSE_CHARS - response.length();
            String delta = event.summary();
            int end = Math.min(delta.length(), remaining);
            if (end > 0 && end < delta.length()
                && Character.isHighSurrogate(delta.charAt(end - 1))
                && Character.isLowSurrogate(delta.charAt(end))) {
                end--;
            }
            response.append(delta, 0, end);
        } else if (event.type() == RuntimeEventType.CANCELLED) {
            terminal.cancelled = true;
        } else if (event.type() == RuntimeEventType.FAILED) {
            terminal.failed = true;
            terminal.failure = new IllegalStateException(event.summary());
        } else if (event.type() == RuntimeEventType.APPROVAL_REQUIRED) {
            terminal.approval = persisted;
        } else if ((event.type() == RuntimeEventType.TOOL_RESULT_DELTA
                || event.type() == RuntimeEventType.TOOL_RESULT_FINISHED)
            && persisted.view().projection().containsKey("userQuestion")) {
            terminal.userQuestion = persisted;
        }
    }

    /**
     * 表示{@code Terminal}相关的领域对象。
     */
    private static final class Terminal {
        private boolean cancelled;
        private boolean failed;
        private PersistedRuntimeEvent approval;
        private PersistedRuntimeEvent userQuestion;
        private Throwable failure;
    }
}
