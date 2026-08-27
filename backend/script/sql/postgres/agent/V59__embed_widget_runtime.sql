-- agent platform schema V59: short-lived Embed widget credentials and durable controls

BEGIN;

CREATE TABLE IF NOT EXISTS agent_embed_browser_credential (
    id                  BIGINT PRIMARY KEY,
    token_hash          CHAR(64) NOT NULL,
    token_kind          VARCHAR(16) NOT NULL,
    application_id      BIGINT NOT NULL,
    api_credential_id   BIGINT NOT NULL,
    service_account_id  BIGINT NOT NULL,
    agent_version_id    BIGINT NOT NULL,
    host_origin         VARCHAR(512) NOT NULL,
    external_user_hash  CHAR(64) NOT NULL,
    session_minutes     INTEGER NOT NULL,
    session_id          BIGINT,
    expires_at          TIMESTAMP NOT NULL,
    consumed_at         TIMESTAMP,
    revoked_at          TIMESTAMP,
    last_used_at        TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_embed_browser_token UNIQUE (token_hash),
    CONSTRAINT ck_agent_embed_browser_kind CHECK (token_kind IN ('launch', 'session')),
    CONSTRAINT ck_agent_embed_browser_hashes CHECK (
        token_hash ~ '^[0-9a-f]{64}$' AND external_user_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_embed_browser_minutes CHECK (session_minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_agent_embed_browser_binding CHECK (
        (token_kind = 'launch' AND session_id IS NULL)
        OR (token_kind = 'session' AND session_id IS NOT NULL)
    ),
    CONSTRAINT ck_agent_embed_browser_expiry CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS idx_agent_embed_browser_application
    ON agent_embed_browser_credential (application_id, expires_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_embed_browser_session
    ON agent_embed_browser_credential (session_id, expires_at DESC)
    WHERE session_id IS NOT NULL;

ALTER TABLE agent_embed_turn
    ADD COLUMN IF NOT EXISTS stop_requested_at TIMESTAMP;

ALTER TABLE agent_embed_turn
    DROP CONSTRAINT IF EXISTS ck_agent_embed_turn_status;

ALTER TABLE agent_embed_turn
    ADD CONSTRAINT ck_agent_embed_turn_status
    CHECK (status IN ('running', 'stopping', 'succeeded', 'failed', 'cancelled'));

COMMENT ON TABLE agent_embed_browser_credential IS 'Embed浏览器短期启动与会话凭证，仅保存令牌哈希';
COMMENT ON COLUMN agent_embed_browser_credential.id IS '浏览器凭证主键';
COMMENT ON COLUMN agent_embed_browser_credential.token_hash IS '浏览器凭证SHA-256哈希';
COMMENT ON COLUMN agent_embed_browser_credential.token_kind IS '凭证类型：启动或会话';
COMMENT ON COLUMN agent_embed_browser_credential.application_id IS '所属API应用ID';
COMMENT ON COLUMN agent_embed_browser_credential.api_credential_id IS '签发来源API凭证ID';
COMMENT ON COLUMN agent_embed_browser_credential.service_account_id IS '服务账号ID';
COMMENT ON COLUMN agent_embed_browser_credential.agent_version_id IS '限定Agent版本ID';
COMMENT ON COLUMN agent_embed_browser_credential.host_origin IS '限定宿主页面Origin';
COMMENT ON COLUMN agent_embed_browser_credential.external_user_hash IS '外部用户标识哈希';
COMMENT ON COLUMN agent_embed_browser_credential.session_minutes IS '允许创建的会话时长分钟数';
COMMENT ON COLUMN agent_embed_browser_credential.session_id IS '绑定Embed会话ID';
COMMENT ON COLUMN agent_embed_browser_credential.expires_at IS '凭证过期时间';
COMMENT ON COLUMN agent_embed_browser_credential.consumed_at IS '启动凭证消费时间';
COMMENT ON COLUMN agent_embed_browser_credential.revoked_at IS '凭证撤销时间';
COMMENT ON COLUMN agent_embed_browser_credential.last_used_at IS '凭证最后使用时间';
COMMENT ON COLUMN agent_embed_browser_credential.created_at IS '凭证创建时间';
COMMENT ON COLUMN agent_embed_turn.stop_requested_at IS 'Embed回合持久停止请求时间';

COMMIT;
