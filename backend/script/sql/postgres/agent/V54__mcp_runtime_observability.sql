-- MCP runtime mounts, circuit-breaker health and secret-free service usage facts.
-- Logical references remain application-managed to match the existing platform schema.

BEGIN;

CREATE TABLE IF NOT EXISTS agent_mcp_runtime_health (
    connector_id             BIGINT PRIMARY KEY,
    health_status            VARCHAR(16) NOT NULL DEFAULT 'unknown',
    circuit_state            VARCHAR(16) NOT NULL DEFAULT 'closed',
    consecutive_failures     INTEGER NOT NULL DEFAULT 0,
    total_connections        BIGINT NOT NULL DEFAULT 0,
    total_reconnections      BIGINT NOT NULL DEFAULT 0,
    total_invocations        BIGINT NOT NULL DEFAULT 0,
    total_successes          BIGINT NOT NULL DEFAULT 0,
    total_failures           BIGINT NOT NULL DEFAULT 0,
    last_success_at          TIMESTAMP,
    last_failure_at          TIMESTAMP,
    last_reconnect_at        TIMESTAMP,
    circuit_open_until       TIMESTAMP,
    last_latency_ms          BIGINT,
    last_error_summary       VARCHAR(1000),
    updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revision_no              BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT ck_agent_mcp_runtime_health_status
        CHECK (health_status IN ('unknown', 'healthy', 'degraded', 'unavailable')),
    CONSTRAINT ck_agent_mcp_runtime_circuit_state
        CHECK (circuit_state IN ('closed', 'open', 'half_open')),
    CONSTRAINT ck_agent_mcp_runtime_health_counts
        CHECK (
            consecutive_failures >= 0
            AND total_connections >= 0
            AND total_reconnections >= 0
            AND total_invocations >= 0
            AND total_successes >= 0
            AND total_failures >= 0
        ),
    CONSTRAINT ck_agent_mcp_runtime_health_latency
        CHECK (last_latency_ms IS NULL OR last_latency_ms >= 0)
);

CREATE TABLE IF NOT EXISTS agent_mcp_runtime_mount (
    id                       BIGINT PRIMARY KEY,
    connector_id             BIGINT NOT NULL,
    connector_revision       BIGINT NOT NULL,
    scope_type               VARCHAR(16) NOT NULL,
    scope_key                VARCHAR(200) NOT NULL,
    user_id                  BIGINT NOT NULL,
    conversation_id          BIGINT,
    task_id                  BIGINT,
    run_id                   BIGINT,
    step_id                  BIGINT,
    session_id               VARCHAR(128) NOT NULL,
    execution_id             VARCHAR(128) NOT NULL,
    trace_id                 VARCHAR(64) NOT NULL,
    status                   VARCHAR(16) NOT NULL,
    connection_attempts      INTEGER NOT NULL DEFAULT 0,
    reconnect_count          INTEGER NOT NULL DEFAULT 0,
    invocation_count         BIGINT NOT NULL DEFAULT 0,
    failure_count            BIGINT NOT NULL DEFAULT 0,
    opened_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at             TIMESTAMP,
    closed_at                TIMESTAMP,
    last_error_summary       VARCHAR(1000),
    CONSTRAINT ck_agent_mcp_runtime_mount_scope
        CHECK (scope_type IN ('session', 'run')),
    CONSTRAINT ck_agent_mcp_runtime_mount_status
        CHECK (status IN ('mounting', 'mounted', 'idle', 'degraded', 'closed', 'expired', 'abandoned')),
    CONSTRAINT ck_agent_mcp_runtime_mount_counts
        CHECK (
            connector_revision > 0
            AND connection_attempts >= 0
            AND reconnect_count >= 0
            AND invocation_count >= 0
            AND failure_count >= 0
        ),
    CONSTRAINT ck_agent_mcp_runtime_mount_identity
        CHECK (
            (scope_type = 'run' AND run_id IS NOT NULL)
            OR (scope_type = 'session' AND conversation_id IS NOT NULL)
        )
);

CREATE TABLE IF NOT EXISTS agent_mcp_usage_detail (
    id                       BIGINT PRIMARY KEY,
    mount_id                 BIGINT NOT NULL,
    connector_id             BIGINT NOT NULL,
    connector_revision       BIGINT NOT NULL,
    tool_id                  BIGINT NOT NULL,
    external_tool_name       VARCHAR(255) NOT NULL,
    user_id                  BIGINT NOT NULL,
    conversation_id          BIGINT,
    task_id                  BIGINT,
    run_id                   BIGINT,
    step_id                  BIGINT,
    session_id               VARCHAR(128) NOT NULL,
    execution_id             VARCHAR(128) NOT NULL,
    trace_id                 VARCHAR(64) NOT NULL,
    status                   VARCHAR(24) NOT NULL,
    attempt_count            INTEGER NOT NULL,
    latency_ms               BIGINT NOT NULL,
    request_bytes            BIGINT NOT NULL,
    response_bytes           BIGINT,
    error_summary            VARCHAR(1000),
    started_at               TIMESTAMP NOT NULL,
    completed_at             TIMESTAMP NOT NULL,
    CONSTRAINT ck_agent_mcp_usage_status
        CHECK (status IN ('success', 'provider_error', 'transport_error', 'circuit_open')),
    CONSTRAINT ck_agent_mcp_usage_metrics
        CHECK (
            connector_revision > 0
            AND attempt_count >= 0 AND attempt_count <= 2
            AND latency_ms >= 0
            AND request_bytes >= 0
            AND (response_bytes IS NULL OR response_bytes >= 0)
        )
);

CREATE INDEX IF NOT EXISTS idx_agent_mcp_runtime_health_updated
    ON agent_mcp_runtime_health (health_status, circuit_state, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_mcp_runtime_mount_connector_opened
    ON agent_mcp_runtime_mount (connector_id, opened_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_mcp_runtime_mount_active
    ON agent_mcp_runtime_mount (connector_id, status, last_used_at DESC)
    WHERE status IN ('mounting', 'mounted', 'idle', 'degraded');
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_mcp_runtime_mount_active_scope
    ON agent_mcp_runtime_mount (connector_id, connector_revision, scope_type, scope_key)
    WHERE status IN ('mounting', 'mounted', 'idle', 'degraded');
CREATE INDEX IF NOT EXISTS idx_agent_mcp_usage_connector_started
    ON agent_mcp_usage_detail (connector_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_mcp_usage_run_started
    ON agent_mcp_usage_detail (run_id, started_at DESC)
    WHERE run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_mcp_usage_session_started
    ON agent_mcp_usage_detail (user_id, session_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_mcp_usage_status_started
    ON agent_mcp_usage_detail (status, started_at DESC);

COMMENT ON TABLE agent_mcp_runtime_health IS 'MCP连接器实时健康与熔断状态';
COMMENT ON COLUMN agent_mcp_runtime_health.connector_id IS 'MCP连接器ID';
COMMENT ON COLUMN agent_mcp_runtime_health.health_status IS '健康状态：unknown未知、healthy健康、degraded降级、unavailable不可用';
COMMENT ON COLUMN agent_mcp_runtime_health.circuit_state IS '熔断状态：closed关闭、open开启、half_open半开探测';
COMMENT ON COLUMN agent_mcp_runtime_health.consecutive_failures IS '连续传输失败次数';
COMMENT ON COLUMN agent_mcp_runtime_health.total_connections IS '累计成功建连次数';
COMMENT ON COLUMN agent_mcp_runtime_health.total_reconnections IS '累计成功重连次数';
COMMENT ON COLUMN agent_mcp_runtime_health.total_invocations IS '累计工具调用次数';
COMMENT ON COLUMN agent_mcp_runtime_health.total_successes IS '累计成功调用次数';
COMMENT ON COLUMN agent_mcp_runtime_health.total_failures IS '累计失败调用次数';
COMMENT ON COLUMN agent_mcp_runtime_health.last_success_at IS '最近成功时间';
COMMENT ON COLUMN agent_mcp_runtime_health.last_failure_at IS '最近失败时间';
COMMENT ON COLUMN agent_mcp_runtime_health.last_reconnect_at IS '最近重连成功时间';
COMMENT ON COLUMN agent_mcp_runtime_health.circuit_open_until IS '熔断开启截止时间';
COMMENT ON COLUMN agent_mcp_runtime_health.last_latency_ms IS '最近一次调用耗时毫秒';
COMMENT ON COLUMN agent_mcp_runtime_health.last_error_summary IS '最近一次脱敏错误摘要';
COMMENT ON COLUMN agent_mcp_runtime_health.updated_at IS '状态更新时间';
COMMENT ON COLUMN agent_mcp_runtime_health.revision_no IS '状态更新修订号';

COMMENT ON TABLE agent_mcp_runtime_mount IS 'MCP会话级或运行级连接挂载生命周期';
COMMENT ON COLUMN agent_mcp_runtime_mount.id IS '挂载记录ID';
COMMENT ON COLUMN agent_mcp_runtime_mount.connector_id IS 'MCP连接器ID';
COMMENT ON COLUMN agent_mcp_runtime_mount.connector_revision IS '挂载时连接器配置修订号';
COMMENT ON COLUMN agent_mcp_runtime_mount.scope_type IS '挂载范围：session会话级、run运行级';
COMMENT ON COLUMN agent_mcp_runtime_mount.scope_key IS '服务端生成的挂载范围唯一键';
COMMENT ON COLUMN agent_mcp_runtime_mount.user_id IS '发起运行的用户ID';
COMMENT ON COLUMN agent_mcp_runtime_mount.conversation_id IS '会话ID';
COMMENT ON COLUMN agent_mcp_runtime_mount.task_id IS '任务ID';
COMMENT ON COLUMN agent_mcp_runtime_mount.run_id IS '任务运行ID';
COMMENT ON COLUMN agent_mcp_runtime_mount.step_id IS '任务步骤ID';
COMMENT ON COLUMN agent_mcp_runtime_mount.session_id IS 'AgentScope会话标识';
COMMENT ON COLUMN agent_mcp_runtime_mount.execution_id IS 'AgentScope执行标识';
COMMENT ON COLUMN agent_mcp_runtime_mount.trace_id IS '全链路追踪标识';
COMMENT ON COLUMN agent_mcp_runtime_mount.status IS '挂载状态：挂载中、已挂载、空闲、降级、关闭、过期或异常遗留';
COMMENT ON COLUMN agent_mcp_runtime_mount.connection_attempts IS '连接尝试次数';
COMMENT ON COLUMN agent_mcp_runtime_mount.reconnect_count IS '成功重连次数';
COMMENT ON COLUMN agent_mcp_runtime_mount.invocation_count IS '通过该挂载执行的调用次数';
COMMENT ON COLUMN agent_mcp_runtime_mount.failure_count IS '通过该挂载产生的失败次数';
COMMENT ON COLUMN agent_mcp_runtime_mount.opened_at IS '挂载创建时间';
COMMENT ON COLUMN agent_mcp_runtime_mount.last_used_at IS '最近使用时间';
COMMENT ON COLUMN agent_mcp_runtime_mount.closed_at IS '挂载关闭时间';
COMMENT ON COLUMN agent_mcp_runtime_mount.last_error_summary IS '最近一次脱敏错误摘要';

COMMENT ON TABLE agent_mcp_usage_detail IS 'MCP服务调用计量明细，不保存请求或响应正文';
COMMENT ON COLUMN agent_mcp_usage_detail.id IS '调用明细ID';
COMMENT ON COLUMN agent_mcp_usage_detail.mount_id IS 'MCP运行挂载ID';
COMMENT ON COLUMN agent_mcp_usage_detail.connector_id IS 'MCP连接器ID';
COMMENT ON COLUMN agent_mcp_usage_detail.connector_revision IS '调用时连接器配置修订号';
COMMENT ON COLUMN agent_mcp_usage_detail.tool_id IS '平台工具版本ID';
COMMENT ON COLUMN agent_mcp_usage_detail.external_tool_name IS '远端MCP工具名称快照';
COMMENT ON COLUMN agent_mcp_usage_detail.user_id IS '调用用户ID';
COMMENT ON COLUMN agent_mcp_usage_detail.conversation_id IS '会话ID';
COMMENT ON COLUMN agent_mcp_usage_detail.task_id IS '任务ID';
COMMENT ON COLUMN agent_mcp_usage_detail.run_id IS '任务运行ID';
COMMENT ON COLUMN agent_mcp_usage_detail.step_id IS '任务步骤ID';
COMMENT ON COLUMN agent_mcp_usage_detail.session_id IS 'AgentScope会话标识';
COMMENT ON COLUMN agent_mcp_usage_detail.execution_id IS 'AgentScope执行标识';
COMMENT ON COLUMN agent_mcp_usage_detail.trace_id IS '全链路追踪标识';
COMMENT ON COLUMN agent_mcp_usage_detail.status IS '调用状态：成功、服务错误、传输错误或熔断拒绝';
COMMENT ON COLUMN agent_mcp_usage_detail.attempt_count IS '实际远端调用次数，熔断拒绝为0';
COMMENT ON COLUMN agent_mcp_usage_detail.latency_ms IS '端到端调用耗时毫秒';
COMMENT ON COLUMN agent_mcp_usage_detail.request_bytes IS '序列化请求参数字节数';
COMMENT ON COLUMN agent_mcp_usage_detail.response_bytes IS '序列化响应字节数';
COMMENT ON COLUMN agent_mcp_usage_detail.error_summary IS '脱敏错误摘要';
COMMENT ON COLUMN agent_mcp_usage_detail.started_at IS '调用开始时间';
COMMENT ON COLUMN agent_mcp_usage_detail.completed_at IS '调用完成时间';

COMMIT;
