package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeCancellationResult;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.platform.embed.service.EmbedChatPersistenceService.TurnStart;
import group.aitools.nhs.platform.execution.service.AgentRuntimeExecutionService;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * 表示嵌入式会话对话执行Coordinator相关的领域对象。
 * Owns Embed execution independently from any browser SSE subscription. */
@Service
public class EmbedChatExecutionCoordinator {

    private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LEASE_TIMEOUT = Duration.ofSeconds(30);
    private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_RESPONSE_CHARS = 1024 * 1024;

    private final ObjectProvider<AgentRuntimeExecutionService> runtimeProvider;
    private final EmbedRuntimeSnapshotFactory snapshots;
    private final EmbedChatPersistenceService persistence;
    private final Executor executor;
    private final String executionOwner;
    private final ConcurrentMap<Long, AgentRunRequest> active = new ConcurrentHashMap<>();

    @Autowired
    public EmbedChatExecutionCoordinator(
        ObjectProvider<AgentRuntimeExecutionService> runtimeProvider,
        EmbedRuntimeSnapshotFactory snapshots,
        EmbedChatPersistenceService persistence
    ) {
        this(
            runtimeProvider, snapshots, persistence,
            command -> Thread.ofVirtual().name("agent-embed-turn").start(command),
            "embed-" + UUID.randomUUID()
        );
    }

    /**
     * 创建 {@code EmbedChatExecutionCoordinator} 实例并初始化所需依赖。
     *
     * @param runtimeProvider 运行时提供方参数
     * @param snapshots {@code snapshots}参数
     * @param persistence {@code persistence}参数
     * @param executor {@code executor}参数
     */
    EmbedChatExecutionCoordinator(
        ObjectProvider<AgentRuntimeExecutionService> runtimeProvider,
        EmbedRuntimeSnapshotFactory snapshots,
        EmbedChatPersistenceService persistence,
        Executor executor
    ) {
        this(runtimeProvider, snapshots, persistence, executor, "embed-test-" + UUID.randomUUID());
    }

    /**
     * 创建 {@code EmbedChatExecutionCoordinator} 实例并初始化所需依赖。
     *
     * @param runtimeProvider 运行时提供方参数
     * @param snapshots {@code snapshots}参数
     * @param persistence {@code persistence}参数
     * @param executor {@code executor}参数
     * @param executionOwner 执行Owner参数
     */
    EmbedChatExecutionCoordinator(
        ObjectProvider<AgentRuntimeExecutionService> runtimeProvider,
        EmbedRuntimeSnapshotFactory snapshots,
        EmbedChatPersistenceService persistence,
        Executor executor,
        String executionOwner
    ) {
        this.runtimeProvider = runtimeProvider;
        this.snapshots = snapshots;
        this.persistence = persistence;
        this.executor = executor;
        this.executionOwner = executionOwner;
    }

    /**
     * 处理{@code launch}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param start {@code start}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean launch(AuthenticatedServiceAccount authenticated, TurnStart start) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (active.containsKey(start.turn().getId())) return true;
        AgentRuntimeExecutionService runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            throw new IllegalStateException("AgentScope运行时未启用");
        }
        AgentRunRequest request = start.media().isEmpty()
            ? snapshots.build(authenticated.principal(), start.session(), start.turn(), start.input())
            : snapshots.build(
                authenticated.principal(), start.session(), start.turn(), start.input(), start.media()
            );
        if (active.putIfAbsent(start.turn().getId(), request) != null) return true;
        try {
            if (!persistence.claimExecution(start.turn().getId(), executionOwner)) {
                active.remove(start.turn().getId(), request);
                return false;
            }
            executor.execute(() -> execute(runtime, request, start));
        } catch (RuntimeException exception) {
            active.remove(start.turn().getId(), request);
            throw exception;
        }
        return true;
    }

    /**
     * 处理{@code recoverAbandonedExecutions}相关逻辑。
     */
    @Scheduled(
        fixedDelayString = "${agent.embed.execution-recovery-delay-ms:15000}",
        initialDelayString = "${agent.embed.execution-recovery-initial-delay-ms:15000}"
    )
    public void recoverAbandonedExecutions() {
        persistence.finishStaleExecutions(LEASE_TIMEOUT);
    }

    /**
     * 处理{@code requestStop}并返回对应结果。
     *
     * @param turnId 资源标识
     * @param reason {@code reason}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean requestStop(Long turnId, String reason) {
        AgentRunRequest request = active.get(turnId);
        AgentRuntimeExecutionService runtime = runtimeProvider.getIfAvailable();
        if (request == null || runtime == null) return false;
        RuntimeCancellationResult result = runtime.cancel(request.executionKey(), reason)
            .block(CANCEL_TIMEOUT);
        return result != null && result.interruptRequested();
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param runtime 运行时参数
     * @param request 请求参数
     * @param start {@code start}参数
     */
    private void execute(
        AgentRuntimeExecutionService runtime,
        AgentRunRequest request,
        TurnStart start
    ) {
        StringBuilder response = new StringBuilder();
        Terminal terminal = new Terminal();
        Thread watcher = Thread.ofVirtual()
            .name("agent-embed-cancel-watch-" + start.turn().getId())
            .start(() -> watchCancellation(runtime, request, start.turn().getId()));
        try {
            runtime.runInternal(request)
                .doOnNext(event -> accept(event.source(), response, terminal))
                .blockLast();
            String status = terminal.cancelled || persistence.stopRequested(start.turn().getId())
                ? "cancelled" : terminal.failed ? "failed" : "succeeded";
            persistence.finishOwned(
                start.session(), start.turn(), executionOwner,
                status, response.toString(), terminal.failure
            );
        } catch (RuntimeException exception) {
            String status = persistence.stopRequested(start.turn().getId()) ? "cancelled" : "failed";
            persistence.finishOwned(
                start.session(), start.turn(), executionOwner,
                status, response.toString(), exception
            );
        } finally {
            watcher.interrupt();
            active.remove(start.turn().getId(), request);
        }
    }

    /**
     * 处理{@code watchCancellation}相关逻辑。
     *
     * @param runtime 运行时参数
     * @param request 请求参数
     * @param turnId 资源标识
     */
    private void watchCancellation(
        AgentRuntimeExecutionService runtime,
        AgentRunRequest request,
        Long turnId
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        long nextHeartbeat = 0L;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long now = System.nanoTime();
                    if (now >= nextHeartbeat) {
                        if (!persistence.heartbeatExecution(turnId, executionOwner)) {
                            runtime.cancel(request.executionKey(), "Embed执行租约已失效")
                                .block(CANCEL_TIMEOUT);
                            return;
                        }
                        nextHeartbeat = now + HEARTBEAT_NANOS;
                    }
                    if (persistence.stopRequested(turnId)) {
                        runtime.cancel(request.executionKey(), "用户停止Embed回复").block(CANCEL_TIMEOUT);
                        return;
                    }
                } catch (RuntimeException ignored) {
                    // Retry transient database errors while the execution remains active.
                }
                Thread.sleep(250L);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理{@code accept}相关逻辑。
     *
     * @param event 事件参数
     * @param response {@code response}参数
     * @param terminal {@code terminal}参数
     */
    private void accept(RuntimeEvent event, StringBuilder response, Terminal terminal) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (event.type() == RuntimeEventType.CUSTOM
            && Boolean.TRUE.equals(event.payload().get("retraction"))) {
            response.setLength(0);
            response.append(event.summary());
        } else if (event.type() == RuntimeEventType.TEXT_DELTA && response.length() < MAX_RESPONSE_CHARS) {
            String delta = event.summary();
            int remaining = MAX_RESPONSE_CHARS - response.length();
            int end = Math.min(delta.length(), remaining);
            if (end > 0 && end < delta.length()
                && Character.isHighSurrogate(delta.charAt(end - 1))
                && Character.isLowSurrogate(delta.charAt(end))) end--;
            response.append(delta, 0, end);
        } else if (event.type() == RuntimeEventType.CANCELLED) {
            terminal.cancelled = true;
        } else if (event.type() == RuntimeEventType.FAILED
            || event.type() == RuntimeEventType.APPROVAL_REQUIRED) {
            terminal.failed = true;
            terminal.failure = new IllegalStateException(event.summary());
        }
    }

    /**
     * 表示{@code Terminal}相关的领域对象。
     */
    private static final class Terminal {
        private boolean cancelled;
        private boolean failed;
        private Throwable failure;
    }
}
