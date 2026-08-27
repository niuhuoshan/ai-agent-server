package group.aitools.nhs.platform.search.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.search.domain.SearchInvocation;
import group.aitools.nhs.platform.search.domain.SearchProviderState;
import group.aitools.nhs.platform.search.mapper.SearchProviderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 负责Search运行时Persistence相关的业务编排与领域规则处理。
 * Commits search health, circuit and content-free audit facts independently. */
@Service
public class SearchRuntimePersistenceService {

    private final SearchProviderMapper mapper;
    private final PlatformIdGenerator idGenerator;

    public SearchRuntimePersistenceService(
        SearchProviderMapper mapper,
        PlatformIdGenerator idGenerator
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 处理{@code state}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    public SearchProviderState state(Long connectorId) {
        return mapper.selectState(connectorId);
    }

    /**
     * 处理{@code recentInvocations}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param since {@code since}参数
     * @return 处理结果
     */
    public int recentInvocations(Long connectorId, LocalDateTime since) {
        return mapper.countRecentInvocations(connectorId, since);
    }

    /**
     * 处理{@code acquireHalfOpenProbe}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param now {@code now}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquireHalfOpenProbe(Long connectorId, LocalDateTime now) {
        return mapper.acquireHalfOpenProbe(connectorId, now) == 1;
    }

    /**
     * 处理{@code success}相关逻辑。
     *
     * @param connectorId 资源标识
     * @param revision {@code revision}参数
     * @param latencyMs {@code latencyMs}参数
     * @param now {@code now}参数
     * @param audit 审计参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(
        Long connectorId,
        Long revision,
        int latencyMs,
        LocalDateTime now,
        InvocationAudit audit
    ) {
        mapper.markSuccess(connectorId, latencyMs, now);
        mapper.markConnectorHealthy(connectorId, revision, now);
        insert(audit, connectorId, "succeeded", latencyMs, null, now);
    }

    /**
     * 处理{@code failure}相关逻辑。
     *
     * @param connectorId 资源标识
     * @param revision {@code revision}参数
     * @param failureThreshold {@code failureThreshold}参数
     * @param cooldownSeconds {@code cooldownSeconds}参数
     * @param latencyMs {@code latencyMs}参数
     * @param errorCode {@code errorCode}参数
     * @param error {@code error}参数
     * @param now {@code now}参数
     * @param audit 审计参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(
        Long connectorId,
        Long revision,
        int failureThreshold,
        int cooldownSeconds,
        int latencyMs,
        String errorCode,
        String error,
        LocalDateTime now,
        InvocationAudit audit
    ) {
        mapper.markFailure(
            connectorId, failureThreshold, latencyMs, error, now,
            now.plusSeconds(cooldownSeconds)
        );
        mapper.markConnectorUnhealthy(connectorId, revision, error, now);
        insert(audit, connectorId, "failed", latencyMs, errorCode, now);
    }

    /**
     * 处理{@code rejected}相关逻辑。
     *
     * @param connectorId 资源标识
     * @param status 目标状态
     * @param errorCode {@code errorCode}参数
     * @param now {@code now}参数
     * @param audit 审计参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejected(
        Long connectorId,
        String status,
        String errorCode,
        LocalDateTime now,
        InvocationAudit audit
    ) {
        insert(audit, connectorId, status, null, errorCode, now);
    }

    /**
     * 创建并保存{@code insert}。
     *
     * @param source 数据源参数
     * @param connectorId 资源标识
     * @param status 目标状态
     * @param latencyMs {@code latencyMs}参数
     * @param errorCode {@code errorCode}参数
     * @param now {@code now}参数
     */
    private void insert(
        InvocationAudit source,
        Long connectorId,
        String status,
        Integer latencyMs,
        String errorCode,
        LocalDateTime now
    ) {
        SearchInvocation invocation = new SearchInvocation();
        invocation.setId(idGenerator.nextId());
        invocation.setConnectorId(connectorId);
        invocation.setActorId(source.actorId());
        invocation.setRunId(source.runId());
        invocation.setTraceId(source.traceId());
        invocation.setQuerySha256(source.querySha256());
        invocation.setResultCount(source.resultCount());
        invocation.setStatus(status);
        invocation.setLatencyMs(latencyMs);
        invocation.setErrorCode(errorCode);
        invocation.setOccurredAt(now);
        mapper.insertInvocation(invocation);
    }

    /**
     * 封装调用审计相关的不可变数据。
     */
    public record InvocationAudit(
        Long actorId,
        String runId,
        String traceId,
        String querySha256,
        int resultCount
    ) {
    }
}
