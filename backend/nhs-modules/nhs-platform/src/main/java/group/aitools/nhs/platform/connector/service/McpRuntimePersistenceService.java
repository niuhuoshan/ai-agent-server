package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.McpRuntimeHealth;
import group.aitools.nhs.platform.connector.domain.McpRuntimeMount;
import group.aitools.nhs.platform.connector.domain.McpUsageDetail;
import group.aitools.nhs.platform.connector.mapper.McpRuntimeMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 负责Mcp运行时Persistence相关的业务编排与领域规则处理。
 * Commits MCP operational facts independently from the caller's business transaction. */
@Service
public class McpRuntimePersistenceService {

    private final McpRuntimeMapper mapper;
    private final PlatformIdGenerator idGenerator;

    public McpRuntimePersistenceService(McpRuntimeMapper mapper, PlatformIdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 创建并保存{@code Mount}。
     *
     * @param request 请求参数
     * @param connector 连接器参数
     * @param scopeType 业务类型
     * @param scopeKey 范围Key参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public McpRuntimeMount createMount(
        AgentRunRequest request,
        AgentConnector connector,
        String scopeType,
        String scopeKey,
        LocalDateTime now
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        McpRuntimeMount existing = mapper.selectActiveMount(
            connector.getId(), connector.getRevisionNo(), scopeType, scopeKey
        );
        if (existing != null) {
            return existing;
        }
        McpRuntimeMount mount = new McpRuntimeMount();
        mount.setId(idGenerator.nextId());
        mount.setConnectorId(connector.getId());
        mount.setConnectorRevision(connector.getRevisionNo());
        mount.setScopeType(scopeType);
        mount.setScopeKey(scopeKey);
        mount.setUserId(request.userId());
        mount.setConversationId(request.conversationId());
        mount.setTaskId(request.taskId());
        mount.setRunId(request.runId());
        mount.setStepId(request.stepId());
        mount.setSessionId(bounded(request.sessionId(), 128));
        mount.setExecutionId(bounded(request.executionKey().executionId(), 128));
        mount.setTraceId(bounded(request.executionKey().traceId(), 64));
        mount.setStatus("mounting");
        mount.setConnectionAttempts(0);
        mount.setReconnectCount(0);
        mount.setInvocationCount(0L);
        mount.setFailureCount(0L);
        mount.setOpenedAt(now);
        try {
            mapper.insertMount(mount);
            return mount;
        } catch (DuplicateKeyException exception) {
            McpRuntimeMount concurrent = mapper.selectActiveMount(
                connector.getId(), connector.getRevisionNo(), scopeType, scopeKey
            );
            if (concurrent != null) {
                return concurrent;
            }
            throw exception;
        }
    }

    /**
     * 处理{@code mounted}相关逻辑。
     *
     * @param mountId 资源标识
     * @param reconnected {@code reconnected}参数
     * @param now {@code now}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mounted(Long mountId, boolean reconnected, LocalDateTime now) {
        mapper.markMountMounted(mountId, reconnected ? 1 : 0, now);
    }

    /**
     * 处理{@code active}相关逻辑。
     *
     * @param mountId 资源标识
     * @param now {@code now}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void active(Long mountId, LocalDateTime now) {
        mapper.markMountActive(mountId, now);
    }

    /**
     * 处理{@code connectionFailed}相关逻辑。
     *
     * @param mountId 资源标识
     * @param now {@code now}参数
     * @param error {@code error}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void connectionFailed(Long mountId, LocalDateTime now, String error) {
        mapper.markMountConnectionFailed(mountId, now, error);
    }

    /**
     * 处理{@code used}相关逻辑。
     *
     * @param mountId 资源标识
     * @param failed {@code failed}参数
     * @param now {@code now}参数
     * @param error {@code error}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void used(Long mountId, boolean failed, LocalDateTime now, String error) {
        mapper.markMountUsed(mountId, failed ? 1 : 0, now, error);
    }

    /**
     * 处理{@code idle}相关逻辑。
     *
     * @param mountId 资源标识
     * @param now {@code now}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void idle(Long mountId, LocalDateTime now) {
        mapper.markMountIdle(mountId, now);
    }

    /**
     * 处理{@code close}相关逻辑。
     *
     * @param mountId 资源标识
     * @param status 目标状态
     * @param now {@code now}参数
     * @param error {@code error}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void close(Long mountId, String status, LocalDateTime now, String error) {
        mapper.closeMount(mountId, status, now, error);
    }

    /**
     * 保存健康状态。
     *
     * @param health 健康状态参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveHealth(McpRuntimeHealth health) {
        mapper.upsertHealth(health);
    }

    /**
     * 清理或重置健康状态。
     *
     * @param connectorId 资源标识
     * @param now {@code now}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetHealth(Long connectorId, LocalDateTime now) {
        mapper.resetHealth(connectorId, now);
    }

    /**
     * 处理健康状态并返回对应结果。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    public McpRuntimeHealth health(Long connectorId) {
        return mapper.selectHealth(connectorId);
    }

    /**
     * 处理{@code usage}相关逻辑。
     *
     * @param detail {@code detail}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void usage(McpUsageDetail detail) {
        detail.setId(idGenerator.nextId());
        mapper.insertUsage(detail);
    }

    /**
     * 处理{@code expireIdle}并返回对应结果。
     *
     * @param cutoff {@code cutoff}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireIdle(LocalDateTime cutoff, LocalDateTime now) {
        return mapper.expireIdleMounts(cutoff, now);
    }

    /**
     * 处理{@code abandonStale}并返回对应结果。
     *
     * @param cutoff {@code cutoff}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int abandonStale(LocalDateTime cutoff, LocalDateTime now) {
        return mapper.abandonStaleMounts(cutoff, now);
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String bounded(String value, int maximum) {
        String normalized = value.replace('\0', ' ').strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
