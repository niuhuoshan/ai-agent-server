-- agent platform schema V72: durable state for Nhs auxiliary runtime tools

BEGIN;

CREATE TABLE IF NOT EXISTS agent_runtime_dashboard_context (
    id              BIGINT PRIMARY KEY,
    owner_id        BIGINT NOT NULL,
    conversation_id BIGINT,
    room_name       VARCHAR(255),
    metric_name     VARCHAR(255),
    time_range      VARCHAR(128),
    context_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    revision_no     BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_runtime_dashboard_context_owner CHECK (owner_id > 0),
    CONSTRAINT ck_agent_runtime_dashboard_context_revision CHECK (revision_no > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_runtime_dashboard_context_owner_conversation
    ON agent_runtime_dashboard_context (owner_id, (COALESCE(conversation_id, 0)));

CREATE TABLE IF NOT EXISTS agent_runtime_scratchpad (
    id              BIGINT PRIMARY KEY,
    owner_id        BIGINT NOT NULL,
    session_key     VARCHAR(160) NOT NULL,
    storage_path    VARCHAR(1024) NOT NULL,
    last_used_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_runtime_scratchpad_owner_session UNIQUE (owner_id, session_key),
    CONSTRAINT ck_agent_runtime_scratchpad_owner CHECK (owner_id > 0)
);

CREATE TABLE IF NOT EXISTS agent_runtime_confirmation (
    id              BIGINT PRIMARY KEY,
    confirmation_key VARCHAR(64) NOT NULL,
    owner_id        BIGINT NOT NULL,
    execution_id    VARCHAR(128) NOT NULL,
    conversation_id BIGINT,
    title           VARCHAR(255) NOT NULL,
    fields_json     JSONB NOT NULL DEFAULT '[]'::jsonb,
    ui_json         JSONB NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(32) NOT NULL DEFAULT 'awaiting_user',
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_runtime_confirmation_key UNIQUE (confirmation_key),
    CONSTRAINT ck_agent_runtime_confirmation_owner CHECK (owner_id > 0),
    CONSTRAINT ck_agent_runtime_confirmation_status CHECK (
        status IN ('awaiting_user', 'confirmed', 'cancelled', 'expired')
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_runtime_confirmation_owner_status
    ON agent_runtime_confirmation (owner_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_runtime_delegation (
    id              BIGINT PRIMARY KEY,
    delegation_key  VARCHAR(64) NOT NULL,
    owner_id        BIGINT NOT NULL,
    execution_id    VARCHAR(128) NOT NULL,
    conversation_id BIGINT,
    agent_name      VARCHAR(128) NOT NULL,
    query_text      TEXT NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'queued',
    result_text     TEXT,
    error_summary   VARCHAR(2000),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_runtime_delegation_key UNIQUE (delegation_key),
    CONSTRAINT ck_agent_runtime_delegation_owner CHECK (owner_id > 0),
    CONSTRAINT ck_agent_runtime_delegation_status CHECK (
        status IN ('queued', 'running', 'completed', 'failed', 'cancelled')
    )
);

COMMENT ON TABLE agent_runtime_dashboard_context IS '运行时仪表盘上下文持久状态';
COMMENT ON TABLE agent_runtime_scratchpad IS '用户和会话隔离的 SQLite 临时分析沙箱索引';
COMMENT ON TABLE agent_runtime_confirmation IS '运行时业务确认卡持久状态';
COMMENT ON TABLE agent_runtime_delegation IS '运行时子 Agent 委派队列和结果';
COMMENT ON COLUMN agent_runtime_dashboard_context.owner_id IS '上下文所有者用户ID';
COMMENT ON COLUMN agent_runtime_dashboard_context.conversation_id IS '关联个人会话ID，可为空';
COMMENT ON COLUMN agent_runtime_scratchpad.session_key IS '用户私有临时会话标识';
COMMENT ON COLUMN agent_runtime_confirmation.ui_json IS '前端确认卡展示快照';
COMMENT ON COLUMN agent_runtime_delegation.query_text IS '委派给子 Agent 的问题';

COMMIT;
