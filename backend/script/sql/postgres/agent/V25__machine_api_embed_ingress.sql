-- agent platform schema V25: audited machine API and isolated embed conversations

BEGIN;

ALTER TABLE agent_conversation
    ADD COLUMN IF NOT EXISTS principal_type VARCHAR(24) NOT NULL DEFAULT 'human';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_agent_conversation_principal_type'
    ) THEN
        ALTER TABLE agent_conversation
            ADD CONSTRAINT ck_agent_conversation_principal_type
            CHECK (principal_type IN ('human', 'service_account'));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS agent_api_rate_bucket (
    application_id      BIGINT NOT NULL,
    window_start        TIMESTAMP NOT NULL,
    request_count       INTEGER NOT NULL DEFAULT 0,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (application_id, window_start),
    CONSTRAINT ck_agent_api_rate_count CHECK (request_count >= 0)
);

CREATE TABLE IF NOT EXISTS agent_api_call (
    id                  BIGINT PRIMARY KEY,
    request_id          CHAR(64) NOT NULL,
    application_id      BIGINT NOT NULL,
    credential_id       BIGINT NOT NULL,
    service_account_id  BIGINT NOT NULL,
    endpoint_key        VARCHAR(64) NOT NULL,
    http_method         VARCHAR(8) NOT NULL,
    required_scope      VARCHAR(64) NOT NULL,
    resource_type       VARCHAR(32),
    resource_id         BIGINT,
    outcome             VARCHAR(16) NOT NULL DEFAULT 'accepted',
    status_code         INTEGER,
    request_bytes       INTEGER NOT NULL DEFAULT 0,
    duration_ms         BIGINT,
    error_code          VARCHAR(64),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP,
    CONSTRAINT uk_agent_api_call_request UNIQUE (request_id),
    CONSTRAINT ck_agent_api_call_outcome
        CHECK (outcome IN ('accepted', 'succeeded', 'failed', 'rate_limited')),
    CONSTRAINT ck_agent_api_call_request_bytes CHECK (request_bytes >= 0),
    CONSTRAINT ck_agent_api_call_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_api_call_application
    ON agent_api_call (application_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_api_call_service_account
    ON agent_api_call (service_account_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_embed_session (
    id                  BIGINT PRIMARY KEY,
    session_key         VARCHAR(128) NOT NULL,
    application_id      BIGINT NOT NULL,
    service_account_id  BIGINT NOT NULL,
    agent_version_id    BIGINT NOT NULL,
    conversation_id     BIGINT NOT NULL,
    external_user_hash  CHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    expires_at          TIMESTAMP NOT NULL,
    last_used_at        TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_embed_session_key UNIQUE (session_key),
    CONSTRAINT uk_agent_embed_conversation UNIQUE (conversation_id),
    CONSTRAINT ck_agent_embed_session_status CHECK (status IN ('active', 'closed', 'expired')),
    CONSTRAINT ck_agent_embed_external_user_hash CHECK (external_user_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_embed_session_expiry CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS idx_agent_embed_session_owner
    ON agent_embed_session (application_id, service_account_id, status, expires_at);

CREATE TABLE IF NOT EXISTS agent_embed_turn (
    id                  BIGINT PRIMARY KEY,
    session_id          BIGINT NOT NULL,
    idempotency_hash    CHAR(64) NOT NULL,
    request_hash        CHAR(64) NOT NULL,
    trace_id            CHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'running',
    error_summary       VARCHAR(2000),
    started_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         TIMESTAMP,
    CONSTRAINT uk_agent_embed_turn_key UNIQUE (session_id, idempotency_hash),
    CONSTRAINT uk_agent_embed_turn_trace UNIQUE (trace_id),
    CONSTRAINT ck_agent_embed_turn_hashes CHECK (
        idempotency_hash ~ '^[0-9a-f]{64}$'
        AND request_hash ~ '^[0-9a-f]{64}$'
        AND trace_id ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_embed_turn_status
        CHECK (status IN ('running', 'succeeded', 'failed', 'cancelled'))
);

CREATE INDEX IF NOT EXISTS idx_agent_embed_turn_session
    ON agent_embed_turn (session_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_job_queue_automation_claim
    ON agent_job_queue (job_type, status, available_at, priority DESC, id)
    WHERE status IN ('queued', 'running');

COMMENT ON TABLE agent_api_call IS '开放API调用审计事实，不保存凭证和请求正文';
COMMENT ON TABLE agent_api_rate_bucket IS '开放API集群固定窗口限流计数';
COMMENT ON TABLE agent_embed_session IS '嵌入式聊天应用与机器主体隔离会话';
COMMENT ON TABLE agent_embed_turn IS '嵌入式聊天回合幂等与执行状态';
COMMENT ON COLUMN agent_conversation.principal_type IS '会话所有者主体类型，防止人员与机器ID碰撞';

COMMIT;
