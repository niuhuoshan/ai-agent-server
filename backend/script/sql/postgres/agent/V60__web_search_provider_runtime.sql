-- agent platform schema V60: governed web-search provider runtime state and invocation audit

BEGIN;

CREATE TABLE IF NOT EXISTS agent_search_provider_state (
    connector_id           BIGINT PRIMARY KEY,
    circuit_state          VARCHAR(16) NOT NULL DEFAULT 'closed',
    consecutive_failures   INTEGER NOT NULL DEFAULT 0,
    total_requests         BIGINT NOT NULL DEFAULT 0,
    total_failures         BIGINT NOT NULL DEFAULT 0,
    last_latency_ms        INTEGER,
    last_success_at        TIMESTAMP,
    last_failure_at        TIMESTAMP,
    opened_at              TIMESTAMP,
    next_probe_at          TIMESTAMP,
    last_error             VARCHAR(2000),
    updated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_search_provider_state_circuit CHECK (
        circuit_state IN ('closed', 'open', 'half_open')
    ),
    CONSTRAINT ck_agent_search_provider_state_failures CHECK (
        consecutive_failures >= 0 AND total_requests >= 0 AND total_failures >= 0
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_search_provider_state_probe
    ON agent_search_provider_state (circuit_state, next_probe_at);

CREATE TABLE IF NOT EXISTS agent_search_invocation (
    id                    BIGINT PRIMARY KEY,
    connector_id          BIGINT NOT NULL,
    actor_id              BIGINT,
    run_id                VARCHAR(128),
    trace_id              VARCHAR(128),
    query_sha256          CHAR(64) NOT NULL,
    result_count          INTEGER NOT NULL DEFAULT 0,
    status                VARCHAR(24) NOT NULL,
    latency_ms            INTEGER,
    error_code            VARCHAR(64),
    occurred_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_search_invocation_count CHECK (result_count >= 0),
    CONSTRAINT ck_agent_search_invocation_status CHECK (
        status IN ('succeeded', 'failed', 'rate_limited', 'circuit_open', 'unavailable')
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_search_invocation_connector_time
    ON agent_search_invocation (connector_id, occurred_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_search_invocation_actor_time
    ON agent_search_invocation (actor_id, occurred_at DESC, id DESC);

COMMENT ON TABLE agent_search_provider_state IS '联网搜索Provider持久运行状态与熔断状态表';
COMMENT ON COLUMN agent_search_provider_state.connector_id IS '搜索连接器ID';
COMMENT ON COLUMN agent_search_provider_state.circuit_state IS '熔断状态：closed、open或half_open';
COMMENT ON COLUMN agent_search_provider_state.consecutive_failures IS '连续失败次数';
COMMENT ON COLUMN agent_search_provider_state.total_requests IS '累计请求次数';
COMMENT ON COLUMN agent_search_provider_state.total_failures IS '累计失败次数';
COMMENT ON COLUMN agent_search_provider_state.last_latency_ms IS '最近一次请求耗时毫秒';
COMMENT ON COLUMN agent_search_provider_state.last_success_at IS '最近成功时间';
COMMENT ON COLUMN agent_search_provider_state.last_failure_at IS '最近失败时间';
COMMENT ON COLUMN agent_search_provider_state.opened_at IS '熔断打开时间';
COMMENT ON COLUMN agent_search_provider_state.next_probe_at IS '半开探测允许时间';
COMMENT ON COLUMN agent_search_provider_state.last_error IS '最近一次脱敏错误摘要';
COMMENT ON COLUMN agent_search_provider_state.updated_at IS '状态更新时间';

COMMENT ON TABLE agent_search_invocation IS '联网搜索调用摘要审计表，不保存原始查询正文';
COMMENT ON COLUMN agent_search_invocation.id IS '调用审计主键ID';
COMMENT ON COLUMN agent_search_invocation.connector_id IS '使用的搜索连接器ID';
COMMENT ON COLUMN agent_search_invocation.actor_id IS '发起用户ID';
COMMENT ON COLUMN agent_search_invocation.run_id IS '运行ID';
COMMENT ON COLUMN agent_search_invocation.trace_id IS '追踪ID';
COMMENT ON COLUMN agent_search_invocation.query_sha256 IS '查询内容SHA256摘要';
COMMENT ON COLUMN agent_search_invocation.result_count IS '返回引用数量';
COMMENT ON COLUMN agent_search_invocation.status IS '调用结果状态';
COMMENT ON COLUMN agent_search_invocation.latency_ms IS '调用耗时毫秒';
COMMENT ON COLUMN agent_search_invocation.error_code IS '脱敏错误代码';
COMMENT ON COLUMN agent_search_invocation.occurred_at IS '调用发生时间';

COMMIT;
