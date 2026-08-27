package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentRuntime;
import group.aitools.nhs.runtime.spi.RuntimeCancellationResult;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 表示智能体范围运行时相关的领域对象。
 * AgentScope 2.x adapter with duplicate-run protection and deterministic resource cleanup. */
public final class AgentScopeRuntimeAdapter implements AgentRuntime {

    private final AgentScopeInvocationFactory invocationFactory;
    private final AgentScopeEventMapper eventMapper;
    private final ConcurrentMap<String, ActiveInvocation> activeInvocations = new ConcurrentHashMap<>();

    public AgentScopeRuntimeAdapter(
        AgentScopeInvocationFactory invocationFactory,
        AgentScopeEventMapper eventMapper
    ) {
        this.invocationFactory = Objects.requireNonNull(invocationFactory, "invocationFactory must not be null");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper must not be null");
    }

    /**
     * 处理{@code stream}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Override
    public Flux<RuntimeEvent> stream(AgentRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return execute(
            request.executionKey(),
            () -> invocationFactory.create(request),
            invocation -> invocation.stream(request),
            throwable -> eventMapper.failure(request, throwable),
            (invocation, event) -> eventMapper.map(
                invocation.frozenRunRequest() == null ? request : invocation.frozenRunRequest(), event
            )
        );
    }

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Override
    public Flux<RuntimeEvent> resume(AgentResumeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return execute(
            request.executionKey(),
            () -> invocationFactory.createForResume(request),
            invocation -> invocation.resume(request),
            throwable -> eventMapper.failure(request, throwable),
            (invocation, event) -> {
                AgentRunRequest frozen = invocation.frozenRunRequest();
                return frozen == null
                    ? eventMapper.map(request, event) : eventMapper.map(frozen, event);
            }
        );
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param key {@code key}参数
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    @Override
    public Mono<RuntimeCancellationResult> cancel(RuntimeExecutionKey key, String reason) {
        Objects.requireNonNull(key, "key must not be null");
        return Mono.fromSupplier(() -> {
            ActiveInvocation active = activeInvocations.get(key.executionId());
            if (active == null) {
                return new RuntimeCancellationResult(false, false);
            }
            boolean requested = active.requestCancellation(normalizeReason(reason));
            return new RuntimeCancellationResult(true, requested);
        });
    }

    /**
     * 处理active调用Count并返回对应结果。
     *
     * @return 处理结果
     */
    int activeInvocationCount() {
        return activeInvocations.size();
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param key {@code key}参数
     * @param invocationSupplier 调用Supplier参数
     * @param streamSupplier {@code streamSupplier}参数
     * @param failureMapper {@code failureMapper}参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    private Flux<RuntimeEvent> execute(
        RuntimeExecutionKey key,
        InvocationSupplier invocationSupplier,
        EventStreamSupplier streamSupplier,
        FailureMapper failureMapper,
        EventMapper mapper
    ) {
        return Flux.defer(() -> {
            ActiveInvocation active = new ActiveInvocation();
            ActiveInvocation existing = activeInvocations.putIfAbsent(key.executionId(), active);
            if (existing != null) {
                return Flux.just(failureMapper.map(new IllegalStateException(
                    "execution is already active: " + key.executionId()
                )));
            }

            AgentScopeInvocation invocation;
            try {
                invocation = invocationSupplier.get();
                active.attach(invocation);
            } catch (RuntimeException exception) {
                activeInvocations.remove(key.executionId(), active);
                active.close();
                return Flux.just(failureMapper.map(exception));
            }
            return Flux.defer(() -> streamSupplier.stream(invocation))
                .map(event -> mapper.map(invocation, event))
                .onErrorResume(throwable -> Flux.just(failureMapper.map(throwable)))
                .doFinally(signal -> {
                    activeInvocations.remove(key.executionId(), active);
                    active.close();
                });
        });
    }

    /**
     * 处理{@code normalizeReason}并返回对应结果。
     *
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "Cancelled by platform request" : reason.strip();
    }

    /**
     * 定义调用Supplier相关能力的服务契约。
     */
    @FunctionalInterface
    private interface InvocationSupplier {
        /**
         * 获取{@code get}。
         *
         * @return 处理结果
         */
        AgentScopeInvocation get();
    }

    /**
     * 定义事件StreamSupplier相关能力的服务契约。
     */
    @FunctionalInterface
    private interface EventStreamSupplier {
        /**
         * 处理{@code stream}并返回对应结果。
         *
         * @param invocation 调用参数
         * @return 处理结果
         */
        Flux<io.agentscope.core.event.AgentEvent> stream(AgentScopeInvocation invocation);
    }

    /**
     * 定义{@code Failure}相关的数据访问契约。
     */
    @FunctionalInterface
    private interface FailureMapper {
        /**
         * 将输入数据转换为{@code map}。
         *
         * @param throwable {@code throwable}参数
         * @return 处理结果
         */
        RuntimeEvent map(Throwable throwable);
    }

    /**
     * 定义事件相关的数据访问契约。
     */
    @FunctionalInterface
    private interface EventMapper {
        /**
         * 将输入数据转换为{@code map}。
         *
         * @param invocation 调用参数
         * @param event 事件参数
         * @return 处理结果
         */
        RuntimeEvent map(
            AgentScopeInvocation invocation,
            io.agentscope.core.event.AgentEvent event
        );
    }

    /**
     * 表示Active调用相关的领域对象。
     */
    private static final class ActiveInvocation {

        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile AgentScopeInvocation invocation;
        private volatile String cancellationReason;

        /**
         * 处理{@code attach}相关逻辑。
         *
         * @param invocation 调用参数
         */
        void attach(AgentScopeInvocation invocation) {
            this.invocation = Objects.requireNonNull(invocation, "invocation must not be null");
            if (closed.get()) {
                invocation.close();
            } else if (cancellationRequested.get()) {
                invocation.interrupt(cancellationReason);
            }
        }

        /**
         * 处理{@code requestCancellation}并返回对应结果。
         *
         * @param reason {@code reason}参数
         * @return 判断结果，{@code true} 表示条件成立
         */
        boolean requestCancellation(String reason) {
            if (!cancellationRequested.compareAndSet(false, true)) {
                return false;
            }
            cancellationReason = reason;
            AgentScopeInvocation current = invocation;
            if (current != null) {
                current.interrupt(reason);
            }
            return true;
        }

        /**
         * 处理{@code close}相关逻辑。
         */
        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            AgentScopeInvocation current = invocation;
            if (current != null) {
                current.close();
            }
        }
    }
}
