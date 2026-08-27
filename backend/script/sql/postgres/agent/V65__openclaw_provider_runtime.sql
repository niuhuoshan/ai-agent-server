-- OpenClaw provider sessions, content-free invocation audit, remote tasks and retained artifacts.

BEGIN;

CREATE TABLE IF NOT EXISTS agent_openclaw_session (
    id                  BIGINT PRIMARY KEY,
    connector_id        BIGINT NOT NULL,
    actor_id            BIGINT NOT NULL,
    client_key_hash     CHAR(64) NOT NULL,
    remote_session_key  VARCHAR(96) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_openclaw_session UNIQUE (connector_id, actor_id, client_key_hash)
);

CREATE INDEX IF NOT EXISTS idx_agent_openclaw_session_actor_time
    ON agent_openclaw_session (actor_id, last_used_at DESC);

CREATE TABLE IF NOT EXISTS agent_openclaw_invocation (
    id                  BIGINT PRIMARY KEY,
    connector_id        BIGINT NOT NULL,
    actor_id            BIGINT NOT NULL,
    session_id          BIGINT NOT NULL,
    request_hash        CHAR(64) NOT NULL,
    message_count       INTEGER NOT NULL,
    status              VARCHAR(24) NOT NULL,
    retry_count         INTEGER NOT NULL DEFAULT 0,
    first_token_ms      INTEGER,
    duration_ms         INTEGER,
    error_code          VARCHAR(64),
    occurred_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_openclaw_invocation_status CHECK (
        status IN ('succeeded', 'failed', 'cancelled', 'unavailable')
    ),
    CONSTRAINT ck_agent_openclaw_invocation_counts CHECK (
        message_count > 0 AND retry_count >= 0
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_openclaw_invocation_actor_time
    ON agent_openclaw_invocation (actor_id, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_agent_openclaw_invocation_connector_time
    ON agent_openclaw_invocation (connector_id, occurred_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS agent_openclaw_task (
    id                  BIGINT PRIMARY KEY,
    connector_id        BIGINT NOT NULL,
    actor_id            BIGINT NOT NULL,
    client_task_key     VARCHAR(128) NOT NULL,
    remote_task_id      VARCHAR(255) NOT NULL,
    remote_session_key  VARCHAR(96) NOT NULL,
    objective_hash      CHAR(64) NOT NULL,
    status              VARCHAR(24) NOT NULL,
    artifact_count      INTEGER NOT NULL DEFAULT 0,
    last_error          VARCHAR(2000),
    revision_no         BIGINT NOT NULL DEFAULT 1,
    submitted_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP,
    CONSTRAINT uk_agent_openclaw_task_actor_key UNIQUE (actor_id, client_task_key),
    CONSTRAINT ck_agent_openclaw_task_status CHECK (
        status IN ('queued', 'running', 'paused', 'succeeded', 'failed', 'cancelled')
    ),
    CONSTRAINT ck_agent_openclaw_task_artifacts CHECK (artifact_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_openclaw_task_actor_time
    ON agent_openclaw_task (actor_id, submitted_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_agent_openclaw_task_provider_status
    ON agent_openclaw_task (connector_id, status, updated_at);

CREATE TABLE IF NOT EXISTS agent_openclaw_artifact (
    id                  BIGINT PRIMARY KEY,
    task_id             BIGINT NOT NULL,
    remote_artifact_id  VARCHAR(255) NOT NULL,
    file_name           VARCHAR(255) NOT NULL,
    content_type        VARCHAR(128) NOT NULL,
    byte_size           BIGINT NOT NULL,
    content_sha256      CHAR(64) NOT NULL,
    content_bytes       BYTEA NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_openclaw_artifact_remote UNIQUE (task_id, remote_artifact_id),
    CONSTRAINT ck_agent_openclaw_artifact_size CHECK (byte_size >= 0 AND byte_size <= 8388608)
);

CREATE INDEX IF NOT EXISTS idx_agent_openclaw_artifact_task
    ON agent_openclaw_artifact (task_id, created_at, id);

COMMENT ON TABLE agent_openclaw_session IS 'OpenClaw稳定会话映射表，不保存客户端会话原文';
COMMENT ON COLUMN agent_openclaw_session.id IS '稳定会话映射主键ID';
COMMENT ON COLUMN agent_openclaw_session.connector_id IS 'OpenClaw连接器ID';
COMMENT ON COLUMN agent_openclaw_session.actor_id IS '发起会话的用户ID';
COMMENT ON COLUMN agent_openclaw_session.client_key_hash IS '客户端会话标识SHA256摘要';
COMMENT ON COLUMN agent_openclaw_session.remote_session_key IS '服务端生成并固定的OpenClaw会话键';
COMMENT ON COLUMN agent_openclaw_session.created_at IS '会话映射创建时间';
COMMENT ON COLUMN agent_openclaw_session.last_used_at IS '会话映射最近使用时间';
COMMENT ON TABLE agent_openclaw_invocation IS 'OpenClaw流式会话内容无关调用审计表';
COMMENT ON COLUMN agent_openclaw_invocation.id IS '流式调用审计主键ID';
COMMENT ON COLUMN agent_openclaw_invocation.connector_id IS 'OpenClaw连接器ID';
COMMENT ON COLUMN agent_openclaw_invocation.actor_id IS '调用用户ID';
COMMENT ON COLUMN agent_openclaw_invocation.session_id IS '稳定会话映射ID';
COMMENT ON COLUMN agent_openclaw_invocation.request_hash IS '消息角色与正文的SHA256摘要';
COMMENT ON COLUMN agent_openclaw_invocation.message_count IS '请求消息数量';
COMMENT ON COLUMN agent_openclaw_invocation.status IS '调用状态：成功、失败、取消或不可用';
COMMENT ON COLUMN agent_openclaw_invocation.retry_count IS '首Token前实际重试次数';
COMMENT ON COLUMN agent_openclaw_invocation.first_token_ms IS '首Token延迟毫秒';
COMMENT ON COLUMN agent_openclaw_invocation.duration_ms IS '调用总耗时毫秒';
COMMENT ON COLUMN agent_openclaw_invocation.error_code IS '脱敏错误代码';
COMMENT ON COLUMN agent_openclaw_invocation.occurred_at IS '调用发生时间';
COMMENT ON TABLE agent_openclaw_task IS 'OpenClaw远端异步任务本地控制事实表';
COMMENT ON COLUMN agent_openclaw_task.id IS '本地异步任务主键ID';
COMMENT ON COLUMN agent_openclaw_task.connector_id IS 'OpenClaw连接器ID';
COMMENT ON COLUMN agent_openclaw_task.actor_id IS '任务提交用户ID';
COMMENT ON COLUMN agent_openclaw_task.client_task_key IS '客户端任务幂等键';
COMMENT ON COLUMN agent_openclaw_task.remote_task_id IS 'OpenClaw远端任务ID';
COMMENT ON COLUMN agent_openclaw_task.remote_session_key IS '任务使用的服务端稳定会话键';
COMMENT ON COLUMN agent_openclaw_task.objective_hash IS '任务目标SHA256摘要，不保存任务正文';
COMMENT ON COLUMN agent_openclaw_task.status IS '任务状态：排队、运行、暂停、成功、失败或取消';
COMMENT ON COLUMN agent_openclaw_task.artifact_count IS '本地已保存产物数量';
COMMENT ON COLUMN agent_openclaw_task.last_error IS '最近一次脱敏错误摘要';
COMMENT ON COLUMN agent_openclaw_task.revision_no IS '乐观锁修订号';
COMMENT ON COLUMN agent_openclaw_task.submitted_at IS '任务提交时间';
COMMENT ON COLUMN agent_openclaw_task.updated_at IS '任务最近更新时间';
COMMENT ON COLUMN agent_openclaw_task.completed_at IS '任务终态完成时间';
COMMENT ON TABLE agent_openclaw_artifact IS 'OpenClaw异步任务有界产物表';
COMMENT ON COLUMN agent_openclaw_artifact.id IS '本地产物主键ID';
COMMENT ON COLUMN agent_openclaw_artifact.task_id IS '所属OpenClaw任务ID';
COMMENT ON COLUMN agent_openclaw_artifact.remote_artifact_id IS 'OpenClaw远端产物ID';
COMMENT ON COLUMN agent_openclaw_artifact.file_name IS '经过路径清理的下载文件名';
COMMENT ON COLUMN agent_openclaw_artifact.content_type IS '产物MIME类型';
COMMENT ON COLUMN agent_openclaw_artifact.byte_size IS '产物字节数';
COMMENT ON COLUMN agent_openclaw_artifact.content_sha256 IS '产物内容SHA256摘要';
COMMENT ON COLUMN agent_openclaw_artifact.content_bytes IS '从已鉴权Provider拉取并受本地权限保护的产物内容';
COMMENT ON COLUMN agent_openclaw_artifact.created_at IS '产物入库时间';

COMMIT;
