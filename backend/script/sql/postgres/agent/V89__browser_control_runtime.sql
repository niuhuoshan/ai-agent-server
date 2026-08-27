-- agent platform schema V89: isolated browser control sessions, events, and leases

BEGIN;

CREATE TABLE IF NOT EXISTS agent_browser_session (
    id                  bigint       PRIMARY KEY,
    owner_id            bigint       NOT NULL,
    session_key         varchar(128) NOT NULL,
    worker_session_id   varchar(255),
    profile_key         varchar(128),
    status              varchar(32)  NOT NULL DEFAULT 'open',
    current_url         varchar(2048),
    page_title          varchar(512),
    created_at          timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at           timestamp,
    CONSTRAINT uk_agent_browser_session_key UNIQUE (session_key),
    CONSTRAINT ck_agent_browser_session_status CHECK (status IN ('opening', 'open', 'closing', 'closed', 'failed'))
);

COMMENT ON TABLE agent_browser_session IS '浏览器控制会话';
COMMENT ON COLUMN agent_browser_session.id IS '会话主键';
COMMENT ON COLUMN agent_browser_session.owner_id IS '会话所属用户或服务账号';
COMMENT ON COLUMN agent_browser_session.session_key IS '平台生成的会话标识';
COMMENT ON COLUMN agent_browser_session.worker_session_id IS '隔离浏览器 Worker 会话标识';
COMMENT ON COLUMN agent_browser_session.profile_key IS '浏览器配置档标识';
COMMENT ON COLUMN agent_browser_session.status IS '会话状态：opening/open/closing/closed/failed';
COMMENT ON COLUMN agent_browser_session.current_url IS '最近一次页面地址';
COMMENT ON COLUMN agent_browser_session.page_title IS '最近一次页面标题';
COMMENT ON COLUMN agent_browser_session.created_at IS '创建时间';
COMMENT ON COLUMN agent_browser_session.updated_at IS '更新时间';
COMMENT ON COLUMN agent_browser_session.closed_at IS '关闭时间';

CREATE INDEX IF NOT EXISTS idx_agent_browser_session_owner_updated
    ON agent_browser_session (owner_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_browser_session_status
    ON agent_browser_session (status);

CREATE TABLE IF NOT EXISTS agent_browser_event (
    id                  bigint       PRIMARY KEY,
    session_id          bigint       NOT NULL,
    owner_id            bigint       NOT NULL,
    event_type          varchar(64)  NOT NULL,
    status              varchar(32)  NOT NULL,
    request_json        jsonb,
    response_json       jsonb,
    error_message       varchar(2000),
    created_at          timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE agent_browser_event IS '浏览器控制事件审计';
COMMENT ON COLUMN agent_browser_event.id IS '事件主键';
COMMENT ON COLUMN agent_browser_event.session_id IS '所属浏览器会话';
COMMENT ON COLUMN agent_browser_event.owner_id IS '事件所属用户或服务账号';
COMMENT ON COLUMN agent_browser_event.event_type IS '事件类型：open/navigate/snapshot/click/fill/close';
COMMENT ON COLUMN agent_browser_event.status IS '事件状态：success/failed';
COMMENT ON COLUMN agent_browser_event.request_json IS '脱敏后的请求参数';
COMMENT ON COLUMN agent_browser_event.response_json IS '受限后的 Worker 响应';
COMMENT ON COLUMN agent_browser_event.error_message IS '失败原因';
COMMENT ON COLUMN agent_browser_event.created_at IS '事件时间';

CREATE INDEX IF NOT EXISTS idx_agent_browser_event_session_created
    ON agent_browser_event (session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_browser_event_owner_created
    ON agent_browser_event (owner_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_browser_lease (
    id                  bigint       PRIMARY KEY,
    session_id          bigint       NOT NULL,
    owner_id            bigint       NOT NULL,
    worker_id           varchar(128) NOT NULL,
    lease_token         varchar(128) NOT NULL,
    lease_until         timestamp    NOT NULL,
    created_at          timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_browser_lease_session UNIQUE (session_id)
);

COMMENT ON TABLE agent_browser_lease IS '浏览器 Worker 会话租约';
COMMENT ON COLUMN agent_browser_lease.id IS '租约主键';
COMMENT ON COLUMN agent_browser_lease.session_id IS '浏览器会话';
COMMENT ON COLUMN agent_browser_lease.owner_id IS '租约所属用户或服务账号';
COMMENT ON COLUMN agent_browser_lease.worker_id IS 'Worker 实例标识';
COMMENT ON COLUMN agent_browser_lease.lease_token IS '租约令牌';
COMMENT ON COLUMN agent_browser_lease.lease_until IS '租约过期时间';
COMMENT ON COLUMN agent_browser_lease.created_at IS '创建时间';
COMMENT ON COLUMN agent_browser_lease.updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_agent_browser_lease_expiry
    ON agent_browser_lease (lease_until);

COMMIT;
