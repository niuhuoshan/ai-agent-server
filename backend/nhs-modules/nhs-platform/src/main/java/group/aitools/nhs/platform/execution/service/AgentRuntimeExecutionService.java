package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentRuntime;
import group.aitools.nhs.runtime.spi.RuntimeCancellationResult;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.portal.quota.service.PortalQuotaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * 负责智能体运行时执行相关的业务编排与领域规则处理。
 * Guarantees that runtime events are persisted before any caller can expose them. */
@Service
@ConditionalOnProperty(
    prefix = "agent.runtime.agentscope",
    name = "enabled",
    havingValue = "true"
)
public class AgentRuntimeExecutionService {

    private final AgentRuntime runtime;
    private final ExecutionEventPersistenceService eventPersistenceService;
    private final PortalQuotaService quotaService;

    /**
     * 创建 {@code AgentRuntimeExecutionService} 实例并初始化所需依赖。
     *
     * @param runtime 运行时参数
     * @param eventPersistenceService 事件PersistenceService参数
     * @param quotaService {@code quotaService}参数
     */
    @Autowired
    public AgentRuntimeExecutionService(
        AgentRuntime runtime,
        ExecutionEventPersistenceService eventPersistenceService,
        PortalQuotaService quotaService
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.eventPersistenceService = Objects.requireNonNull(
            eventPersistenceService, "eventPersistenceService must not be null"
        );
        this.quotaService = quotaService;
    }

    /**
     * 创建 {@code AgentRuntimeExecutionService} 实例并初始化所需依赖。
     *
     * @param runtime 运行时参数
     * @param eventPersistenceService 事件PersistenceService参数
     */
    public AgentRuntimeExecutionService(
        AgentRuntime runtime,
        ExecutionEventPersistenceService eventPersistenceService
    ) {
        this(runtime, eventPersistenceService, null);
    }

    /**
     * 执行{@code run}相关的处理流程。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public Flux<ExecutionEventView> run(AgentRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireQuota(request);
        return eventPersistenceService.persist(stream(request));
    }

    /**
     * 执行{@code Internal}相关的处理流程。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public Flux<PersistedRuntimeEvent> runInternal(AgentRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireQuota(request);
        return eventPersistenceService.persistWithSource(stream(request));
    }

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public Flux<ExecutionEventView> resume(AgentResumeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireQuota(request.userId());
        return eventPersistenceService.persist(runtime.resume(request));
    }

    /**
     * 处理{@code resumeInternal}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public Flux<PersistedRuntimeEvent> resumeInternal(AgentResumeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireQuota(request.userId());
        return eventPersistenceService.persistWithSource(runtime.resume(request));
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param key {@code key}参数
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    public Mono<RuntimeCancellationResult> cancel(RuntimeExecutionKey key, String reason) {
        RuntimeExecutionKey required = Objects.requireNonNull(key, "key must not be null");
        return runtime.cancel(required, reason);
    }

    /**
     * 处理{@code stream}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private Flux<group.aitools.nhs.runtime.spi.RuntimeEvent> stream(AgentRunRequest request) {
        return runtime.stream(request);
    }

    /**
     * 校验{@code Quota}，并在条件不满足时终止处理。
     *
     * @param request 请求参数
     */
    private void requireQuota(AgentRunRequest request) {
        Object principalType = request.authorizationSnapshot().get("principalType");
        if ("service_account".equals(String.valueOf(principalType))) {
            return;
        }
        requireQuota(request.userId());
    }

    /**
     * 校验{@code Quota}，并在条件不满足时终止处理。
     *
     * @param userId 资源标识
     */
    private void requireQuota(Long userId) {
        if (quotaService != null) {
            quotaService.requireAvailable(userId);
        }
    }
}
