-- agent platform schema V34: durable human conversation turns and controlled attachments

BEGIN;

CREATE TABLE IF NOT EXISTS agent_conversation_turn (
    id                  BIGINT PRIMARY KEY,
    conversation_id     BIGINT NOT NULL,
    user_id             BIGINT NOT NULL,
    idempotency_hash    CHAR(64) NOT NULL,
    request_hash        CHAR(64) NOT NULL,
    trace_id            CHAR(64) NOT NULL,
    agent_id            BIGINT NOT NULL,
    agent_version_id    BIGINT NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'running',
    runtime_snapshot_json JSONB NOT NULL,
    error_summary       VARCHAR(2000),
    stop_requested_at   TIMESTAMP,
    started_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         TIMESTAMP,
    CONSTRAINT uk_agent_conversation_turn_key UNIQUE (conversation_id, idempotency_hash),
    CONSTRAINT uk_agent_conversation_turn_trace UNIQUE (trace_id),
    CONSTRAINT ck_agent_conversation_turn_hashes CHECK (
        idempotency_hash ~ '^[0-9a-f]{64}$'
        AND request_hash ~ '^[0-9a-f]{64}$'
        AND trace_id ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_conversation_turn_status CHECK (
        status IN ('running', 'stopping', 'succeeded', 'failed', 'cancelled')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_conversation_turn_active
    ON agent_conversation_turn (conversation_id)
    WHERE status IN ('running', 'stopping');

CREATE INDEX IF NOT EXISTS idx_agent_conversation_turn_owner
    ON agent_conversation_turn (user_id, conversation_id, started_at DESC);

CREATE TABLE IF NOT EXISTS agent_conversation_attachment (
    id                  BIGINT PRIMARY KEY,
    conversation_id     BIGINT NOT NULL,
    user_id             BIGINT NOT NULL,
    turn_id             BIGINT,
    original_name       VARCHAR(255) NOT NULL,
    storage_type        VARCHAR(16) NOT NULL DEFAULT 'local',
    storage_ref         VARCHAR(255) NOT NULL,
    mime_type           VARCHAR(128) NOT NULL,
    size_bytes          BIGINT NOT NULL,
    sha256              CHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'ready',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_conversation_attachment_ref UNIQUE (storage_type, storage_ref),
    CONSTRAINT ck_agent_conversation_attachment_hash CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_conversation_attachment_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    CONSTRAINT ck_agent_conversation_attachment_status CHECK (status IN ('ready', 'bound', 'deleted'))
);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_attachment_owner
    ON agent_conversation_attachment (user_id, conversation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_attachment_turn
    ON agent_conversation_attachment (turn_id, id)
    WHERE turn_id IS NOT NULL;

COMMENT ON TABLE agent_conversation_turn IS '人类私有会话回合的幂等、路由、运行快照和终态事实';
COMMENT ON TABLE agent_conversation_attachment IS '人类私有会话受控附件元数据，文件内容使用不透明存储引用';

COMMIT;
