package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import jakarta.annotation.PreDestroy;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.McpRuntimeHealth;
import group.aitools.nhs.platform.connector.domain.McpRuntimeMount;
import group.aitools.nhs.platform.connector.domain.McpUsageDetail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 负责Mcp运行时Lifecycle相关的业务编排与领域规则处理。
 *
 * Owns real MCP SDK sessions at the AgentScope run/session boundary. Session mounts are retained
 * briefly between conversation turns; formal task-run mounts close at the terminal invocation.
 */
@Service
public class McpRuntimeLifecycleService {

    private final McpRuntimePersistenceService persistence;
    private final ConnectorMcpConnectionFactory connectionFactory;
    private final McpRemoteClient remoteClient;
    private final JsonMapper jsonMapper;
    private final Duration sessionIdleTimeout;
    private final Duration circuitOpenDuration;
    private final int failureThreshold;
    private final ConcurrentHashMap<MountKey, ActiveMount> mounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<MountKey>> executionMounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CircuitTracker> circuits = new ConcurrentHashMap<>();

    public McpRuntimeLifecycleService(
        McpRuntimePersistenceService persistence,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        JsonMapper jsonMapper,
        @Value("${agent.runtime.mcp.session-idle-ms:300000}") long sessionIdleMs,
        @Value("${agent.runtime.mcp.circuit-open-ms:30000}") long circuitOpenMs,
        @Value("${agent.runtime.mcp.failure-threshold:3}") int failureThreshold
    ) {
        this.persistence = persistence;
        this.connectionFactory = connectionFactory;
        this.remoteClient = remoteClient;
        this.jsonMapper = jsonMapper;
        if (sessionIdleMs < 1000 || sessionIdleMs > 3_600_000) {
            throw new IllegalArgumentException("sessionIdleMs must be between 1000 and 3600000");
        }
        if (circuitOpenMs < 1000 || circuitOpenMs > 600_000) {
            throw new IllegalArgumentException("circuitOpenMs must be between 1000 and 600000");
        }
        if (failureThreshold < 1 || failureThreshold > 20) {
            throw new IllegalArgumentException("failureThreshold must be between 1 and 20");
        }
        this.sessionIdleTimeout = Duration.ofMillis(sessionIdleMs);
        this.circuitOpenDuration = Duration.ofMillis(circuitOpenMs);
        this.failureThreshold = failureThreshold;
    }

    /**
 * 处理{@code prepare}相关逻辑。
 * Eagerly initializes a connector mount while AgentScope materializes its frozen toolkit. */
    public void prepare(AgentRunRequest request, AgentConnector connector) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (!runtimeMcp(connector)) {
            return;
        }
        ActiveMount mount = acquire(request, connector);
        CircuitTracker circuit = circuit(connector.getId());
        LocalDateTime now = LocalDateTime.now();
        try {
            if (circuit.beforeAttempt(now)) {
                save(circuit);
            }
            mount.ensureConnected(circuit, now);
        } catch (CircuitOpenException ignored) {
            // The persisted open state is the source of truth exposed to operators.
        } catch (McpRemoteException exception) {
            circuit.connectionFailure(now, safeError(exception), circuitOpenDuration);
            save(circuit);
        }
    }

    /**
 * 执行{@code invoke}相关的处理流程。
 * Invokes through the retained SDK client, reconnecting once on transport loss. */
    public McpRemoteClient.InvocationResult invoke(
        AgentRunRequest request,
        AgentConnector connector,
        Long toolId,
        String externalToolName,
        Map<String, Object> arguments,
        String argumentsJson
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        ActiveMount mount = acquire(request, connector);
        CircuitTracker circuit = circuit(connector.getId());
        LocalDateTime startedAt = LocalDateTime.now();
        long startedNanos = System.nanoTime();
        int attempts = 0;
        McpRemoteClient.InvocationResult result = null;
        String status = "transport_error";
        String error = null;
        McpRemoteException remoteFailure = null;

        try {
            LocalDateTime now = LocalDateTime.now();
            if (circuit.beforeAttempt(now)) {
                save(circuit);
            }
            for (int attempt = 1; attempt <= 2; attempt++) {
                attempts = attempt;
                try {
                    mount.ensureConnected(circuit, LocalDateTime.now());
                    result = mount.invoke(externalToolName, arguments);
                    status = result.error() ? "provider_error" : "success";
                    break;
                } catch (McpRemoteException exception) {
                    remoteFailure = exception;
                    mount.disconnect();
                    if (attempt == 2) {
                        error = safeError(exception);
                    }
                }
            }
        } catch (CircuitOpenException exception) {
            attempts = 0;
            status = "circuit_open";
            error = safeError(exception);
            remoteFailure = exception;
        }

        long latencyMs = elapsedMillis(startedNanos);
        LocalDateTime completedAt = LocalDateTime.now();
        if (result != null) {
            if (result.error()) {
                circuit.providerError(completedAt, latencyMs);
                error = "MCP 服务返回了工具执行错误";
            } else {
                circuit.success(completedAt, latencyMs);
            }
        } else if ("circuit_open".equals(status)) {
            circuit.rejected(completedAt, latencyMs, error);
        } else {
            circuit.transportFailure(completedAt, latencyMs, error, circuitOpenDuration);
        }
        save(circuit);

        McpUsageDetail usage = usage(
            request, connector, mount.record, toolId, externalToolName, status, attempts,
            latencyMs, utf8Length(argumentsJson), serializedBytes(result), error,
            startedAt, completedAt
        );
        persistence.usage(usage);
        persistence.used(mount.record.getId(), !"success".equals(status), completedAt, error);

        if (result == null) {
            throw remoteFailure == null
                ? new McpRemoteException("MCP 工具调用失败")
                : new McpRemoteException("MCP 工具调用失败：" + safeError(remoteFailure), remoteFailure);
        }
        return result;
    }

    /**
 * 处理{@code end}相关逻辑。
 * Releases every mount leased by one AgentScope invocation. */
    public void end(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (request == null) {
            return;
        }
        String executionId = request.executionKey().executionId();
        Set<MountKey> keys = executionMounts.remove(executionId);
        if (keys == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (MountKey key : keys) {
            ActiveMount mount = mounts.get(key);
            if (mount == null || !mount.release(executionId, now)) {
                continue;
            }
            if ("run".equals(key.scopeType())) {
                if (mount.close("closed", now, null)) {
                    mounts.remove(key, mount);
                }
            } else {
                persistence.idle(mount.record.getId(), now);
            }
        }
    }

    /**
 * 处理invalidate连接器相关逻辑。
 * Immediately retires every retained session after a connector configuration/state change. */
    public void invalidateConnector(Long connectorId, String reason) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (connectorId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String summary = bounded(
            reason == null || reason.isBlank() ? "MCP 连接器配置已变更，运行挂载已释放" : reason,
            1000
        );
        for (Map.Entry<MountKey, ActiveMount> entry : mounts.entrySet()) {
            if (!connectorId.equals(entry.getKey().connectorId())) {
                continue;
            }
            ActiveMount mount = entry.getValue();
            if (mount.close("closed", now, summary)) {
                mounts.remove(entry.getKey(), mount);
            }
        }
        executionMounts.forEach((executionId, keys) -> {
            keys.removeIf(key -> connectorId.equals(key.connectorId()));
            if (keys.isEmpty()) {
                executionMounts.remove(executionId, keys);
            }
        });
        circuits.remove(connectorId);
        persistence.resetHealth(connectorId, now);
    }

    /**
     * 处理{@code cleanupIdleMounts}相关逻辑。
     */
    @Scheduled(fixedDelayString = "${agent.runtime.mcp.cleanup-delay-ms:30000}")
    public void cleanupIdleMounts() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(sessionIdleTimeout);
        for (Map.Entry<MountKey, ActiveMount> entry : mounts.entrySet()) {
            ActiveMount mount = entry.getValue();
            if (!"session".equals(entry.getKey().scopeType()) || !mount.idleBefore(cutoff)) {
                continue;
            }
            if (mount.close("expired", now, "会话挂载空闲超时，已自动释放")) {
                mounts.remove(entry.getKey(), mount);
            }
        }
        persistence.expireIdle(cutoff, now);
        persistence.abandonStale(now.minusHours(1), now);
    }

    /**
     * 处理{@code closeAll}相关逻辑。
     */
    @PreDestroy
    public void closeAll() {
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<MountKey, ActiveMount> entry : mounts.entrySet()) {
            ActiveMount mount = entry.getValue();
            if (mount.close("closed", now, "服务停止，运行挂载已释放")) {
                mounts.remove(entry.getKey(), mount);
            }
        }
        executionMounts.clear();
    }

    /**
     * 处理{@code activeMountCount}并返回对应结果。
     *
     * @return 处理结果
     */
    int activeMountCount() {
        return mounts.size();
    }

    /**
     * 处理{@code acquire}并返回对应结果。
     *
     * @param request 请求参数
     * @param connector 连接器参数
     * @return 处理结果
     */
    private ActiveMount acquire(AgentRunRequest request, AgentConnector connector) {
        if (!runtimeMcp(connector)) {
            throw new McpRemoteException("MCP 连接器当前不可用");
        }
        MountKey key = key(request, connector);
        ActiveMount mount = mounts.computeIfAbsent(key, ignored -> {
            LocalDateTime now = LocalDateTime.now();
            McpRuntimeMount record = persistence.createMount(
                request, connector, key.scopeType(), key.scopeKey(), now
            );
            return new ActiveMount(connector, record);
        });
        String executionId = request.executionKey().executionId();
        if (mount.acquire(executionId, LocalDateTime.now())) {
            persistence.active(mount.record.getId(), LocalDateTime.now());
        }
        executionMounts.computeIfAbsent(executionId, ignored -> ConcurrentHashMap.newKeySet()).add(key);
        return mount;
    }

    /**
     * 处理{@code key}并返回对应结果。
     *
     * @param request 请求参数
     * @param connector 连接器参数
     * @return 处理结果
     */
    private MountKey key(AgentRunRequest request, AgentConnector connector) {
        String scopeType = request.runId() == null ? "session" : "run";
        String identity = "session".equals(scopeType)
            ? request.userId() + ":" + request.sessionId()
            : request.userId() + ":" + request.runId() + ":" + value(request.stepId());
        return new MountKey(
            connector.getId(), connector.getRevisionNo(), scopeType,
            scopeType + ":" + ContentHashing.sha256(identity)
        );
    }

    /**
     * 处理{@code circuit}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    private CircuitTracker circuit(Long connectorId) {
        return circuits.computeIfAbsent(
            connectorId,
            id -> new CircuitTracker(id, persistence.health(id), failureThreshold)
        );
    }

    /**
     * 保存{@code save}。
     *
     * @param circuit {@code circuit}参数
     */
    private void save(CircuitTracker circuit) {
        persistence.saveHealth(circuit.snapshot());
    }

    /**
     * 执行{@code timeMcp}相关的处理流程。
     *
     * @param connector 连接器参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean runtimeMcp(AgentConnector connector) {
        return connector != null
            && "mcp".equals(connector.getProviderType())
            && "active".equals(connector.getStatus())
            && connector.getId() != null
            && connector.getRevisionNo() != null;
    }

    /**
     * 处理{@code usage}并返回对应结果。
     *
     * @param request 请求参数
     * @param connector 连接器参数
     * @param mount {@code mount}参数
     * @param toolId 资源标识
     * @param externalToolName 名称
     * @param status 目标状态
     * @param attempts {@code attempts}参数
     * @param latencyMs {@code latencyMs}参数
     * @param requestBytes {@code requestBytes}参数
     * @param responseBytes {@code responseBytes}参数
     * @param error {@code error}参数
     * @param startedAt {@code startedAt}参数
     * @param completedAt {@code completedAt}参数
     * @return 处理结果
     */
    private McpUsageDetail usage(
        AgentRunRequest request,
        AgentConnector connector,
        McpRuntimeMount mount,
        Long toolId,
        String externalToolName,
        String status,
        int attempts,
        long latencyMs,
        long requestBytes,
        Long responseBytes,
        String error,
        LocalDateTime startedAt,
        LocalDateTime completedAt
    ) {
        McpUsageDetail detail = new McpUsageDetail();
        detail.setMountId(mount.getId());
        detail.setConnectorId(connector.getId());
        detail.setConnectorRevision(connector.getRevisionNo());
        detail.setToolId(toolId);
        detail.setExternalToolName(bounded(externalToolName, 255));
        detail.setUserId(request.userId());
        detail.setConversationId(request.conversationId());
        detail.setTaskId(request.taskId());
        detail.setRunId(request.runId());
        detail.setStepId(request.stepId());
        detail.setSessionId(bounded(request.sessionId(), 128));
        detail.setExecutionId(bounded(request.executionKey().executionId(), 128));
        detail.setTraceId(bounded(request.executionKey().traceId(), 64));
        detail.setStatus(status);
        detail.setAttemptCount(attempts);
        detail.setLatencyMs(latencyMs);
        detail.setRequestBytes(requestBytes);
        detail.setResponseBytes(responseBytes);
        detail.setErrorSummary(error);
        detail.setStartedAt(startedAt);
        detail.setCompletedAt(completedAt);
        return detail;
    }

    /**
     * 处理{@code serializedBytes}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Long serializedBytes(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return (long) jsonMapper.writeValueAsString(value)
                .getBytes(StandardCharsets.UTF_8).length;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 处理{@code utf8Length}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private long utf8Length(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 处理{@code elapsedMillis}并返回对应结果。
     *
     * @param startedNanos {@code startedNanos}参数
     * @return 处理结果
     */
    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /**
     * 处理{@code safeError}并返回对应结果。
     *
     * @param throwable {@code throwable}参数
     * @return 处理结果
     */
    private String safeError(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "MCP 服务连接或协议处理失败";
        }
        String safe = message
            .replaceAll("(?i)bearer\\s+[^\\s,;]+", "Bearer [REDACTED]")
            .replaceAll("(?i)(token|secret|password|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
        return bounded(safe, 1000);
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String bounded(String value, int maximum) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\0', ' ').strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    /**
     * 处理{@code value}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String value(Long value) {
        return value == null ? "none" : value.toString();
    }

    /**
     * 封装{@code MountKey}相关的不可变数据。
     */
    private record MountKey(
        Long connectorId,
        Long connectorRevision,
        String scopeType,
        String scopeKey
    ) {
    }

    /**
     * 表示{@code ActiveMount}相关的领域对象。
     */
    private final class ActiveMount {

        private final AgentConnector connector;
        private final McpRuntimeMount record;
        private final Set<String> leases = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile McpRemoteClient.McpSession session;
        private volatile int connectionAttempts;
        private volatile LocalDateTime releasedAt;

        /**
         * 创建 {@code ActiveMount} 实例并初始化所需依赖。
         *
         * @param connector 连接器参数
         * @param record {@code record}参数
         */
        private ActiveMount(AgentConnector connector, McpRuntimeMount record) {
            this.connector = connector;
            this.record = record;
            this.connectionAttempts = record.getConnectionAttempts() == null
                ? 0 : record.getConnectionAttempts();
        }

        /**
         * 处理{@code acquire}并返回对应结果。
         *
         * @param executionId 资源标识
         * @param now {@code now}参数
         * @return 判断结果，{@code true} 表示条件成立
         */
        private boolean acquire(String executionId, LocalDateTime now) {
            if (closed.get()) {
                throw new McpRemoteException("MCP 运行挂载已经关闭");
            }
            boolean wasIdle = leases.isEmpty() && releasedAt != null;
            leases.add(executionId);
            releasedAt = null;
            record.setLastUsedAt(now);
            return wasIdle;
        }

        /**
         * 处理{@code release}并返回对应结果。
         *
         * @param executionId 资源标识
         * @param now {@code now}参数
         * @return 判断结果，{@code true} 表示条件成立
         */
        private boolean release(String executionId, LocalDateTime now) {
            leases.remove(executionId);
            if (!leases.isEmpty()) {
                return false;
            }
            releasedAt = now;
            record.setLastUsedAt(now);
            return true;
        }

        /**
         * 校验{@code Connected}，并在条件不满足时终止处理。
         *
         * @param circuit {@code circuit}参数
         * @param now {@code now}参数
         */
        private synchronized void ensureConnected(CircuitTracker circuit, LocalDateTime now) {
            if (session != null) {
                return;
            }
            if (closed.get()) {
                throw new McpRemoteException("MCP 运行挂载已经关闭");
            }
            boolean reconnected = connectionAttempts > 0;
            McpRemoteClient.McpSession opened;
            try {
                opened = remoteClient.open(connectionFactory.create(connector));
            } catch (RuntimeException exception) {
                connectionAttempts++;
                String error = safeError(exception);
                persistence.connectionFailed(record.getId(), now, error);
                throw exception instanceof McpRemoteException remote
                    ? remote : new McpRemoteException(error, exception);
            }
            session = opened;
            connectionAttempts++;
            persistence.mounted(record.getId(), reconnected, now);
            circuit.connected(now, reconnected);
            save(circuit);
        }

        /**
         * 执行{@code invoke}相关的处理流程。
         *
         * @param externalToolName 名称
         * @param arguments {@code arguments}参数
         * @return 处理结果
         */
        private McpRemoteClient.InvocationResult invoke(
            String externalToolName,
            Map<String, Object> arguments
        ) {
            McpRemoteClient.McpSession current = session;
            if (current == null) {
                throw new McpRemoteException("MCP 运行挂载尚未连接");
            }
            try {
                return current.invoke(externalToolName, arguments);
            } catch (RuntimeException exception) {
                throw exception instanceof McpRemoteException remote
                    ? remote : new McpRemoteException("MCP 工具调用失败", exception);
            }
        }

        /**
         * 处理{@code disconnect}相关逻辑。
         */
        private synchronized void disconnect() {
            McpRemoteClient.McpSession current = session;
            session = null;
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException ignored) {
                    // The failed transport is discarded; the persisted call outcome remains authoritative.
                }
            }
        }

        /**
         * 处理{@code idleBefore}并返回对应结果。
         *
         * @param cutoff {@code cutoff}参数
         * @return 判断结果，{@code true} 表示条件成立
         */
        private boolean idleBefore(LocalDateTime cutoff) {
            LocalDateTime value = releasedAt;
            return leases.isEmpty() && value != null && value.isBefore(cutoff);
        }

        /**
         * 处理{@code close}并返回对应结果。
         *
         * @param status 目标状态
         * @param now {@code now}参数
         * @param error {@code error}参数
         * @return 判断结果，{@code true} 表示条件成立
         */
        private boolean close(String status, LocalDateTime now, String error) {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            disconnect();
            persistence.close(record.getId(), status, now, error);
            return true;
        }
    }

    /**
     * 表示{@code CircuitTracker}相关的领域对象。
     */
    private static final class CircuitTracker {

        private final Long connectorId;
        private final int threshold;
        private String healthStatus;
        private String circuitState;
        private int consecutiveFailures;
        private long totalConnections;
        private long totalReconnections;
        private long totalInvocations;
        private long totalSuccesses;
        private long totalFailures;
        private LocalDateTime lastSuccessAt;
        private LocalDateTime lastFailureAt;
        private LocalDateTime lastReconnectAt;
        private LocalDateTime circuitOpenUntil;
        private Long lastLatencyMs;
        private String lastError;
        private long revision;
        private boolean halfOpenProbeInFlight;

        /**
         * 创建 {@code CircuitTracker} 实例并初始化所需依赖。
         *
         * @param connectorId 资源标识
         * @param existing {@code existing}参数
         * @param threshold {@code threshold}参数
         */
        private CircuitTracker(Long connectorId, McpRuntimeHealth existing, int threshold) {
            this.connectorId = connectorId;
            this.threshold = threshold;
            healthStatus = existing == null ? "unknown" : existing.getHealthStatus();
            circuitState = existing == null ? "closed" : existing.getCircuitState();
            consecutiveFailures = existing == null ? 0 : number(existing.getConsecutiveFailures());
            totalConnections = existing == null ? 0 : number(existing.getTotalConnections());
            totalReconnections = existing == null ? 0 : number(existing.getTotalReconnections());
            totalInvocations = existing == null ? 0 : number(existing.getTotalInvocations());
            totalSuccesses = existing == null ? 0 : number(existing.getTotalSuccesses());
            totalFailures = existing == null ? 0 : number(existing.getTotalFailures());
            lastSuccessAt = existing == null ? null : existing.getLastSuccessAt();
            lastFailureAt = existing == null ? null : existing.getLastFailureAt();
            lastReconnectAt = existing == null ? null : existing.getLastReconnectAt();
            circuitOpenUntil = existing == null ? null : existing.getCircuitOpenUntil();
            lastLatencyMs = existing == null ? null : existing.getLastLatencyMs();
            lastError = existing == null ? null : existing.getLastErrorSummary();
            revision = existing == null ? 1 : Math.max(1L, number(existing.getRevisionNo()));
        }

        /**
         * 处理{@code beforeAttempt}并返回对应结果。
         *
         * @param now {@code now}参数
         * @return 判断结果，{@code true} 表示条件成立
         */
        private synchronized boolean beforeAttempt(LocalDateTime now) {
            // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
            if ("half_open".equals(circuitState)) {
                if (halfOpenProbeInFlight) {
                    throw new CircuitOpenException(circuitOpenUntil);
                }
                halfOpenProbeInFlight = true;
                revision++;
                return true;
            }
            if (!"open".equals(circuitState)) {
                return false;
            }
            if (circuitOpenUntil != null && now.isBefore(circuitOpenUntil)) {
                throw new CircuitOpenException(circuitOpenUntil);
            }
            circuitState = "half_open";
            healthStatus = "degraded";
            halfOpenProbeInFlight = true;
            revision++;
            return true;
        }

        /**
         * 处理{@code connected}相关逻辑。
         *
         * @param now {@code now}参数
         * @param reconnected {@code reconnected}参数
         */
        private synchronized void connected(LocalDateTime now, boolean reconnected) {
            totalConnections++;
            if (reconnected) {
                totalReconnections++;
                lastReconnectAt = now;
            }
            healthStatus = "healthy";
            circuitState = "closed";
            consecutiveFailures = 0;
            circuitOpenUntil = null;
            lastSuccessAt = now;
            lastError = null;
            halfOpenProbeInFlight = false;
            revision++;
        }

        /**
         * 处理{@code connectionFailure}相关逻辑。
         *
         * @param now {@code now}参数
         * @param error {@code error}参数
         * @param openDuration {@code openDuration}参数
         */
        private synchronized void connectionFailure(
            LocalDateTime now,
            String error,
            Duration openDuration
        ) {
            fail(now, null, error, false, openDuration);
        }

        /**
         * 处理{@code success}相关逻辑。
         *
         * @param now {@code now}参数
         * @param latencyMs {@code latencyMs}参数
         */
        private synchronized void success(LocalDateTime now, long latencyMs) {
            totalInvocations++;
            totalSuccesses++;
            healthStatus = "healthy";
            circuitState = "closed";
            consecutiveFailures = 0;
            circuitOpenUntil = null;
            lastSuccessAt = now;
            lastLatencyMs = latencyMs;
            lastError = null;
            halfOpenProbeInFlight = false;
            revision++;
        }

        /**
         * 处理提供方Error相关逻辑。
         *
         * @param now {@code now}参数
         * @param latencyMs {@code latencyMs}参数
         */
        private synchronized void providerError(LocalDateTime now, long latencyMs) {
            totalInvocations++;
            totalFailures++;
            healthStatus = "healthy";
            circuitState = "closed";
            consecutiveFailures = 0;
            circuitOpenUntil = null;
            lastFailureAt = now;
            lastLatencyMs = latencyMs;
            lastError = null;
            halfOpenProbeInFlight = false;
            revision++;
        }

        /**
         * 处理{@code transportFailure}相关逻辑。
         *
         * @param now {@code now}参数
         * @param latencyMs {@code latencyMs}参数
         * @param error {@code error}参数
         * @param openDuration {@code openDuration}参数
         */
        private synchronized void transportFailure(
            LocalDateTime now,
            long latencyMs,
            String error,
            Duration openDuration
        ) {
            fail(now, latencyMs, error, true, openDuration);
        }

        /**
         * 处理{@code rejected}相关逻辑。
         *
         * @param now {@code now}参数
         * @param latencyMs {@code latencyMs}参数
         * @param error {@code error}参数
         */
        private synchronized void rejected(LocalDateTime now, long latencyMs, String error) {
            totalInvocations++;
            totalFailures++;
            healthStatus = "unavailable";
            circuitState = "open";
            lastFailureAt = now;
            lastLatencyMs = latencyMs;
            lastError = error;
            halfOpenProbeInFlight = false;
            revision++;
        }

        /**
         * 处理{@code fail}相关逻辑。
         *
         * @param now {@code now}参数
         * @param latencyMs {@code latencyMs}参数
         * @param error {@code error}参数
         * @param invocation 调用参数
         * @param openDuration {@code openDuration}参数
         */
        private void fail(
            LocalDateTime now,
            Long latencyMs,
            String error,
            boolean invocation,
            Duration openDuration
        ) {
            if (invocation) {
                totalInvocations++;
                totalFailures++;
            }
            consecutiveFailures++;
            lastFailureAt = now;
            lastLatencyMs = latencyMs;
            lastError = error;
            if (consecutiveFailures >= threshold) {
                healthStatus = "unavailable";
                circuitState = "open";
                circuitOpenUntil = now.plus(openDuration);
            } else {
                healthStatus = "degraded";
                circuitState = "closed";
            }
            halfOpenProbeInFlight = false;
            revision++;
        }

        /**
         * 处理快照并返回对应结果。
         *
         * @return 处理结果
         */
        private synchronized McpRuntimeHealth snapshot() {
            McpRuntimeHealth value = new McpRuntimeHealth();
            value.setConnectorId(connectorId);
            value.setHealthStatus(healthStatus);
            value.setCircuitState(circuitState);
            value.setConsecutiveFailures(consecutiveFailures);
            value.setTotalConnections(totalConnections);
            value.setTotalReconnections(totalReconnections);
            value.setTotalInvocations(totalInvocations);
            value.setTotalSuccesses(totalSuccesses);
            value.setTotalFailures(totalFailures);
            value.setLastSuccessAt(lastSuccessAt);
            value.setLastFailureAt(lastFailureAt);
            value.setLastReconnectAt(lastReconnectAt);
            value.setCircuitOpenUntil(circuitOpenUntil);
            value.setLastLatencyMs(lastLatencyMs);
            value.setLastErrorSummary(lastError);
            value.setUpdatedAt(LocalDateTime.now());
            value.setRevisionNo(revision);
            return value;
        }

        /**
         * 处理{@code number}并返回对应结果。
         *
         * @param value {@code value}参数
         * @return 处理结果
         */
        private static int number(Integer value) {
            return value == null ? 0 : value;
        }

        /**
         * 处理{@code number}并返回对应结果。
         *
         * @param value {@code value}参数
         * @return 处理结果
         */
        private static long number(Long value) {
            return value == null ? 0 : value;
        }
    }

    /**
     * 表示{@code CircuitOpen}处理过程中发生的业务异常。
     */
    private static final class CircuitOpenException extends McpRemoteException {

        /**
         * 创建 {@code CircuitOpenException} 实例并初始化所需依赖。
         *
         * @param openUntil {@code openUntil}参数
         */
        private CircuitOpenException(LocalDateTime openUntil) {
            super("MCP 连接器熔断中，预计恢复时间：" + openUntil);
        }
    }
}
